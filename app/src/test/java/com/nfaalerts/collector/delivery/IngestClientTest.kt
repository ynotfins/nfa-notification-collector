package com.nfaalerts.collector.delivery

import com.nfaalerts.collector.config.EndpointProfile
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class IngestClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        val held =
            HeldCertificate
                .Builder()
                .commonName("localhost")
                .addSubjectAlternativeName("localhost")
                .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        server = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false) }
        client =
            OkHttpClient
                .Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun postsSchemaOneToSingleHttpsEndpointAndValidatesAcceptedResponse() {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {
                      "accepted": true,
                      "ingestId": "123e4567-e89b-12d3-a456-426614174000",
                      "receivedAt": "2026-08-18T12:00:00-04:00"
                    }
                    """.trimIndent(),
                ),
        )
        server.start()
        val payload =
            WireProjectionResult.Ready(
                source = "bnn",
                bodyBytes =
                    """
                    {
                      "schemaVersion": 1,
                      "source": "bnn",
                      "deviceId": "nfa-primary-phone",
                      "rawText": "raw",
                      "metadata": {}
                    }
                    """.trimIndent().encodeToByteArray(),
                metadata = kotlinx.serialization.json.JsonObject(emptyMap()),
            )
        val credential = CharArray(43) { 'x' }

        val result =
            OkHttpIngestClient(client).send(
                EndpointProfile(server.url("/").toString().removeSuffix("/"), "/v1/ingest/alerts"),
                credential,
                payload,
            )
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!

        assertEquals(
            IngestResult.Sent(
                "123e4567-e89b-12d3-a456-426614174000",
                "2026-08-18T12:00:00-04:00",
            ),
            result,
        )
        assertEquals("POST", recorded.method)
        assertEquals("/v1/ingest/alerts", recorded.path)
        assertEquals("1", recorded.getHeader("X-NFA-Schema-Version"))
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        assertEquals("Bearer ${"x".repeat(43)}", recorded.getHeader("Authorization"))
        assertTrue(credential.all { it == '\u0000' })
    }

    @Test
    fun resultMatrixIsExactAndMalformed202IsQuarantined() {
        val classifier = IngestResponseClassifier()

        listOf(429, 503).forEach { status ->
            assertEquals(IngestResult.RetryWait("HTTP_$status", status), classifier.classify(status, ""))
        }
        assertEquals(IngestResult.PausedAuth("HTTP_401", 401), classifier.classify(401, ""))
        listOf(400, 413, 415, 418, 500).forEach { status ->
            assertEquals(IngestResult.Quarantined("HTTP_$status", status), classifier.classify(status, ""))
        }
        listOf(
            "{}",
            """{"accepted":false,"ingestId":"123e4567-e89b-12d3-a456-426614174000"}""",
            """{"accepted":true,"ingestId":"not-a-uuid","receivedAt":"2026-08-18T12:00:00Z"}""",
            """{"accepted":true,"ingestId":"123e4567-e89b-12d3-a456-426614174000"}""",
        ).forEach { body ->
            assertEquals(IngestResult.Quarantined("MALFORMED_202", 202), classifier.classify(202, body))
        }
        assertEquals(IngestResult.RetryWait("TIMEOUT", null), classifier.networkFailure(SocketTimeoutException()))
        assertEquals(IngestResult.RetryWait("NETWORK", null), classifier.networkFailure(java.io.IOException()))
    }

    @Test
    fun cleartextAndNonBnnRequestsAreRejectedBeforeNetwork() {
        val payload =
            WireProjectionResult.Ready(
                source = "weather",
                bodyBytes = "{}".encodeToByteArray(),
                metadata = kotlinx.serialization.json.JsonObject(emptyMap()),
            )
        val transport = OkHttpIngestClient(client)

        assertTrue(
            runCatching {
                transport.send(
                    EndpointProfile("http://127.0.0.1:8787", "/v1/ingest/alerts"),
                    CharArray(1) { 'x' },
                    payload,
                )
            }.isFailure,
        )
        assertFalse(server.requestCount > 0)
    }

    @Test
    fun runtimeTimeoutConfigurationControlsTheOperationalCall() {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody("{}")
                .setBodyDelay(250, TimeUnit.MILLISECONDS),
        )
        server.start()
        val payload =
            WireProjectionResult.Ready(
                source = "bnn",
                bodyBytes = "{}".encodeToByteArray(),
                metadata = kotlinx.serialization.json.JsonObject(emptyMap()),
            )
        val runtime =
            RuntimeDeliverySettings(
                endpoint = EndpointProfile(server.url("/").toString().removeSuffix("/"), "/v1/ingest/alerts"),
                relevantRevision = 1L,
                bearer = BearerLoad.Present(CharArray(43) { 'x' }),
                readTimeoutMs = 1L,
            )

        val result = OkHttpIngestClient(client).send(runtime, CharArray(43) { 'x' }, payload)

        assertEquals(IngestResult.RetryWait("TIMEOUT", null), result)
    }

    @Test
    fun redirectIsQuarantinedWithoutFollowingAnotherEndpoint() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/unexpected-endpoint"),
        )
        server.enqueue(MockResponse().setResponseCode(202).setBody("{}"))
        server.start()
        val payload =
            WireProjectionResult.Ready(
                source = "bnn",
                bodyBytes = "{}".encodeToByteArray(),
                metadata = kotlinx.serialization.json.JsonObject(emptyMap()),
            )

        val result =
            OkHttpIngestClient(client).send(
                EndpointProfile(server.url("/").toString().removeSuffix("/"), "/v1/ingest/alerts"),
                CharArray(43) { 'x' },
                payload,
            )

        assertEquals(IngestResult.Quarantined("HTTP_302", 302), result)
        assertEquals(1, server.requestCount)
    }
}
