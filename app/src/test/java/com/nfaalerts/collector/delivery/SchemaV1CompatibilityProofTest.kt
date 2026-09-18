package com.nfaalerts.collector.delivery

import com.nfaalerts.collector.config.EndpointProfile
import com.nfaalerts.collector.data.CapturedNotificationEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * M5 schema-v1 compatibility proof against the documented gateway contract.
 *
 * Proves the Android pipeline end to end with a fake endpoint only: the real
 * [WireProjector] projection of a representative synthetic BNN capture is sent
 * by the real [OkHttpIngestClient] to an HTTPS [MockWebServer] that answers
 * with the documented 202 body, and the recorded request must match
 * docs/INGEST-CONTRACT.md exactly. A sentinel bearer proves no output path
 * leaks the credential. No live gateway, database, or real bearer is involved.
 */
class SchemaV1CompatibilityProofTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val sentinelBearer = "m5-sentinel-bearer-000000000000000000000000"
    private val ingestId = "123e4567-e89b-12d3-a456-426614174000"
    private val receivedAt = "2026-09-18T22:00:00Z"

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
    fun androidEquivalentBnnPostMatchesDocumentedSchemaV1AndValidated202Response() {
        server.enqueue(
            MockResponse()
                .setResponseCode(202)
                .setBody(
                    """
                    {
                      "accepted": true,
                      "ingestId": "$ingestId",
                      "attemptId": "223e4567-e89b-12d3-a456-426614174000",
                      "receivedAt": "$receivedAt"
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        val rawText = "BOX 1234 | 500 MAIN ST | MVA ROLLOVER | FD OPS #1900999"
        val capture =
            CapturedNotificationEntity(
                eventId = "123e4567-e89b-12d3-a456-426614174001",
                packageName = "us.bnn.newsapp",
                sourceId = "bnn",
                notificationKey = "us.bnn.newsapp|42|tag|com.example.group",
                notificationId = 42,
                notificationTag = "tag",
                postTimeEpochMillis = 1_755_516_000_000L,
                capturedAtEpochMillis = 1_755_516_100_000L,
                rawText = rawText,
                rawCandidatesJson = """{"bigText":["$rawText"],"text":["short"],"ticker":["ticker"]}""",
                envelopeJson = """{"notification":{"title":"BNN"},"statusBarNotification":{"id":42}}""",
                envelopeSha256 = "abc123",
                envelopeUtf8Bytes = 128,
            )

        val projection = WireProjector().project(capture)
        assertTrue(projection is WireProjectionResult.Ready)
        val credential = sentinelBearer.toCharArray()

        val originalOut = System.out
        val capturedOut = ByteArrayOutputStream()
        System.setOut(PrintStream(capturedOut, true, StandardCharsets.UTF_8.name()))
        val result =
            try {
                OkHttpIngestClient(client).send(
                    EndpointProfile(server.url("/").toString().removeSuffix("/"), "/v1/ingest/alerts"),
                    credential,
                    projection as WireProjectionResult.Ready,
                )
            } finally {
                System.setOut(originalOut)
            }

        assertEquals(IngestResult.Sent(ingestId, receivedAt), result)

        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", recorded.method)
        assertEquals("/v1/ingest/alerts", recorded.path)
        assertEquals("1", recorded.getHeader("X-NFA-Schema-Version"))
        assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
        assertEquals("Bearer $sentinelBearer", recorded.getHeader("Authorization"))

        val bodyText = recorded.body.readUtf8()
        val body = Json.parseToJsonElement(bodyText).jsonObject

        val capturedAt = body.getValue("capturedAt").jsonPrimitive.content
        val metadataText = body.getValue("metadata").toString()

        val schemaVersionText = body.getValue("schemaVersion").jsonPrimitive.content
        assertEquals(1, schemaVersionText.toInt())
        assertEquals("bnn", body.getValue("source").jsonPrimitive.content)
        assertEquals("nfa-primary-phone", body.getValue("deviceId").jsonPrimitive.content)
        assertEquals(rawText, body.getValue("rawText").jsonPrimitive.content)
        assertTrue(Instant.parse(capturedAt) > Instant.EPOCH)
        assertTrue(body.getValue("metadata") is JsonObject)

        assertTrue(bodyText.toByteArray(StandardCharsets.UTF_8).size <= 262_144)
        assertTrue(metadataText.toByteArray(StandardCharsets.UTF_8).size <= 32_768)

        assertFalse(sentinelBearer in bodyText)
        assertFalse(sentinelBearer in result.toString())
        assertFalse(sentinelBearer in capturedOut.toString(StandardCharsets.UTF_8))
        assertTrue(credential.all { it == '\u0000' })
    }
}
