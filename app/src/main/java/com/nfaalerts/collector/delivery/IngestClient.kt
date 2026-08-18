package com.nfaalerts.collector.delivery

import com.nfaalerts.collector.config.EndpointProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface IngestResult {
    data class Sent(
        val ingestId: String,
        val receivedAt: String,
    ) : IngestResult

    data class RetryWait(
        val code: String,
        val httpStatus: Int?,
    ) : IngestResult

    data class PausedAuth(
        val code: String,
        val httpStatus: Int,
    ) : IngestResult

    data class Quarantined(
        val code: String,
        val httpStatus: Int?,
    ) : IngestResult
}

sealed interface BoundedBodyResult {
    data class Text(
        val value: String,
    ) : BoundedBodyResult

    data object TooLarge : BoundedBodyResult
}

class BoundedResponseReader(
    private val maximumBytes: Long = 65_536L,
) {
    fun read(body: ResponseBody): BoundedBodyResult {
        if (body.contentLength() > maximumBytes) return BoundedBodyResult.TooLarge
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (total <= maximumBytes) {
            val remaining = maximumBytes + 1L - total
            val read = source.read(buffer, minOf(8_192L, remaining))
            if (read < 0L) return BoundedBodyResult.Text(buffer.readString(StandardCharsets.UTF_8))
            total += read
        }
        buffer.clear()
        return BoundedBodyResult.TooLarge
    }
}

class IngestResponseClassifier {
    fun classify(
        status: Int,
        body: String,
    ): IngestResult =
        when (status) {
            202 -> parseAccepted(body)
            401 -> IngestResult.PausedAuth("HTTP_401", 401)
            429, 503 -> IngestResult.RetryWait("HTTP_$status", status)
            else -> IngestResult.Quarantined("HTTP_$status", status)
        }

    fun networkFailure(failure: IOException): IngestResult =
        if (failure is SocketTimeoutException) {
            IngestResult.RetryWait("TIMEOUT", null)
        } else {
            IngestResult.RetryWait("NETWORK", null)
        }

    private fun parseAccepted(body: String): IngestResult {
        return try {
            val root = Json.parseToJsonElement(body).jsonObject
            if (root["accepted"]?.jsonPrimitive?.booleanOrNull != true) return malformed()
            val ingestId = root["ingestId"]?.jsonPrimitive?.contentOrNull ?: return malformed()
            UUID.fromString(ingestId)
            val receivedAt = root["receivedAt"]?.jsonPrimitive?.contentOrNull ?: return malformed()
            OffsetDateTime.parse(receivedAt)
            IngestResult.Sent(ingestId, receivedAt)
        } catch (_: IllegalArgumentException) {
            malformed()
        } catch (_: DateTimeParseException) {
            malformed()
        } catch (_: Exception) {
            malformed()
        }
    }

    private fun malformed() = IngestResult.Quarantined("MALFORMED_202", 202)
}

class OkHttpIngestClient(
    private val client: OkHttpClient,
    private val classifier: IngestResponseClassifier = IngestResponseClassifier(),
    private val responseReader: BoundedResponseReader = BoundedResponseReader(),
) {
    fun send(
        settings: RuntimeDeliverySettings,
        bearer: CharArray,
        payload: WireProjectionResult.Ready,
    ): IngestResult =
        send(
            endpoint = settings.endpoint,
            bearer = bearer,
            payload = payload,
            operationalClient =
                client
                    .newBuilder()
                    .connectTimeout(settings.connectTimeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(settings.readTimeoutMs, TimeUnit.MILLISECONDS)
                    .build(),
        )

    fun send(
        endpoint: EndpointProfile,
        bearer: CharArray,
        payload: WireProjectionResult.Ready,
    ): IngestResult = send(endpoint, bearer, payload, client)

    private fun send(
        endpoint: EndpointProfile,
        bearer: CharArray,
        payload: WireProjectionResult.Ready,
        operationalClient: OkHttpClient,
    ): IngestResult {
        require(payload.source == "bnn") { "Only BNN may enter the ingest client." }
        val base = endpoint.baseUrl.toHttpUrl()
        require(base.isHttps) { "Operational endpoint must use HTTPS." }
        require(endpoint.ingestPath.startsWith("/") && !endpoint.ingestPath.startsWith("//")) {
            "Ingest path is invalid."
        }
        val authorizationChars = CharArray(BEARER_PREFIX.length + bearer.size)
        BEARER_PREFIX.toCharArray().copyInto(authorizationChars)
        bearer.copyInto(authorizationChars, BEARER_PREFIX.length)
        val request =
            try {
                Request
                    .Builder()
                    .url(base.newBuilder().encodedPath(endpoint.ingestPath).build())
                    .header("Authorization", String(authorizationChars))
                    .header("X-NFA-Schema-Version", "1")
                    .post(payload.bodyBytes.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            } finally {
                authorizationChars.fill('\u0000')
                bearer.fill('\u0000')
            }
        val restrictedClient =
            operationalClient
                .newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        return try {
            restrictedClient.newCall(request).execute().use { response ->
                if (response.code != 202) {
                    classifier.classify(response.code, "")
                } else {
                    when (val body = responseReader.read(response.body)) {
                        is BoundedBodyResult.Text -> classifier.classify(response.code, body.value)
                        BoundedBodyResult.TooLarge -> IngestResult.Quarantined("MALFORMED_202", 202)
                    }
                }
            }
        } catch (failure: IOException) {
            classifier.networkFailure(failure)
        }
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
