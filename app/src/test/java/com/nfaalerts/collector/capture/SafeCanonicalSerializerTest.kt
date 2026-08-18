package com.nfaalerts.collector.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeCanonicalSerializerTest {
    private val identity =
        EnvelopeIdentity(
            eventId = "event-1",
            packageName = "com.example",
            notificationKey = "key-1",
            notificationId = 9,
            postTimeEpochMillis = 100L,
        )

    @Test
    fun `canonical output sorts keys and preserves unicode strings`() {
        val result =
            SafeCanonicalSerializer().serialize(
                mapOf("z" to 2, "a" to "  Caf\u00E9 \uD83D\uDE92\r\n"),
                identity,
            ) as CanonicalSerialization.Success

        assertTrue(result.json.indexOf("\"a\"") < result.json.indexOf("\"z\""))
        assertTrue(result.json.contains("  Caf\\u00e9").not())
        assertTrue(result.json.contains("Caf\u00E9"))
        assertTrue(result.json.contains("\\r\\n"))
    }

    @Test
    fun `one failing key does not discard sibling values`() {
        val result =
            SafeCanonicalSerializer().serialize(
                PerKeyFailureMap(),
                identity,
            ) as CanonicalSerialization.Success

        assertTrue(result.json.contains("\"good\""))
        assertTrue(result.json.contains("\"per_key_failure\""))
        assertTrue(result.json.contains("\"stillGood\""))
    }

    @Test
    fun `non string map keys retain their values under canonical string keys`() {
        val result =
            SafeCanonicalSerializer().serialize(
                mapOf(7 to "seven"),
                identity,
            ) as CanonicalSerialization.Success

        assertTrue(result.json.contains("\"7\""))
        assertTrue(result.json.contains("seven"))
    }

    @Test
    fun `cycles and executable or binary values use explicit metadata-only markers`() {
        val cyclic = linkedMapOf<String, Any?>()
        cyclic["self"] = cyclic
        val bytes = byteArrayOf(83, 69, 67, 82, 69, 84)
        val result =
            SafeCanonicalSerializer().serialize(
                mapOf(
                    "cycle" to cyclic,
                    "bytes" to SafeOpaqueValue.binary(bytes.size),
                    "pendingIntent" to SafeOpaqueValue.executable("android.app.PendingIntent"),
                ),
                identity,
            ) as CanonicalSerialization.Success

        assertTrue(result.json.contains("\"cycle\""))
        assertTrue(result.json.contains("\"binary_omitted\""))
        assertTrue(result.json.contains("\"executable_omitted\""))
        assertFalse(result.json.contains("SECRET"))
    }

    @Test
    fun `action and message snapshots retain non executable metadata`() {
        val result =
            SafeCanonicalSerializer().serialize(
                mapOf(
                    "action" to
                        SafeActionValue(
                            title = "Open",
                            semanticAction = 1,
                            showsUserInterface = true,
                            authenticationRequired = false,
                            remoteInputs = listOf(SafeRemoteInputValue("reply", "Reply", true, setOf("text/plain"))),
                            hasPendingIntent = true,
                        ),
                    "message" to
                        SafeMessageValue(
                            "Exact message",
                            99L,
                            SafePersonValue("Sender", null, null, false, false, null),
                        ),
                ),
                identity,
            ) as CanonicalSerialization.Success

        assertTrue(result.json.contains("Exact message"))
        assertTrue(result.json.contains("text/plain"))
        assertTrue(result.json.contains("pending_intent_omitted"))
        assertFalse(result.json.contains("intentSender"))
    }

    @Test
    fun `unknown values never execute arbitrary representation`() {
        val unknown = CountingToString()
        val result =
            SafeCanonicalSerializer().serialize(
                mapOf("unknown" to unknown),
                identity,
            ) as CanonicalSerialization.Success

        assertEquals(0, unknown.calls)
        assertTrue(result.json.contains(CountingToString::class.java.name))
        assertTrue(result.json.contains("unsupported_value"))
    }

    @Test
    fun `unordered sets sort by canonical serialized bytes while lists retain order`() {
        val serializer = SafeCanonicalSerializer()
        val first = serializer.serialize(mapOf("set" to linkedSetOf("z", "a")), identity)
        val second = serializer.serialize(mapOf("set" to linkedSetOf("a", "z")), identity)
        val list = serializer.serialize(mapOf("list" to listOf("z", "a")), identity) as CanonicalSerialization.Success

        assertEquals(first, second)
        assertTrue(list.json.indexOf("\"value\":\"z\"") < list.json.indexOf("\"value\":\"a\""))
    }

    @Test
    fun `colliding canonical map keys preserve every entry with collision markers`() {
        val result =
            SafeCanonicalSerializer().serialize(
                linkedMapOf<Any, Any>(1 to "integer", "1" to "string"),
                identity,
            ) as CanonicalSerialization.Success

        assertTrue(result.json.contains("key_collision"))
        assertTrue(result.json.contains("integer"))
        assertTrue(result.json.contains("string"))
    }

    @Test
    fun `map key enumeration failure becomes a bounded root marker`() {
        val result = SafeCanonicalSerializer().serialize(BrokenKeysMap(), identity)

        assertTrue(result is CanonicalSerialization.Success)
        result as CanonicalSerialization.Success
        assertTrue(result.json.contains("key_enumeration_failure"))
    }

    @Test
    fun `repeat serialization is byte deterministic for unsupported values`() {
        val value = mapOf("unknown" to CountingToString(), "set" to hashSetOf("b", "a"))
        val serializer = SafeCanonicalSerializer()

        assertEquals(serializer.serialize(value, identity), serializer.serialize(value, identity))
    }

    @Test
    fun `depth array string node and envelope limits return minimal immutable limit envelopes`() {
        val cases =
            listOf(
                SafeSerializerLimits(maxDepth = 1) to mapOf("nested" to mapOf("tooDeep" to true)),
                SafeSerializerLimits(maxArrayEntries = 1) to mapOf("array" to listOf(1, 2)),
                SafeSerializerLimits(maxStringUtf8Bytes = 3) to mapOf("string" to "four"),
                SafeSerializerLimits(maxNodes = 2) to mapOf("a" to 1, "b" to 2),
                SafeSerializerLimits(maxEnvelopeUtf8Bytes = 70) to mapOf("payload" to "some payload"),
            )

        cases.forEach { (limits, value) ->
            val result = SafeCanonicalSerializer(limits).serialize(value, identity)
            assertTrue("$limits produced $result", result is CanonicalSerialization.LimitExceeded)
            result as CanonicalSerialization.LimitExceeded
            val json = Json.parseToJsonElement(result.minimalEnvelopeJson).jsonObject
            assertEquals("event-1", json.getValue("eventId").jsonPrimitive.content)
            assertEquals(
                true,
                json
                    .getValue("limitEnvelope")
                    .jsonPrimitive.content
                    .toBoolean(),
            )
            assertTrue(result.path.startsWith("$"))
            assertTrue(result.measuredValue > 0)
        }
    }

    private class CountingToString {
        var calls = 0

        override fun toString(): String {
            calls += 1
            return "must-not-run"
        }
    }

    private class BrokenKeysMap : Map<Any, Any?> {
        override val entries: Set<Map.Entry<Any, Any?>>
            get() = error("entries")
        override val keys: Set<Any>
            get() = error("keys")
        override val size: Int = 1
        override val values: Collection<Any?>
            get() = error("values")

        override fun containsKey(key: Any): Boolean = false

        override fun containsValue(value: Any?): Boolean = false

        override fun get(key: Any): Any? = null

        override fun isEmpty(): Boolean = false
    }

    private class PerKeyFailureMap : Map<String, Any?> {
        override val entries: Set<Map.Entry<String, Any?>>
            get() = error("serializer must isolate get per key")
        override val keys: Set<String> = linkedSetOf("good", "broken", "stillGood")
        override val size: Int = keys.size
        override val values: Collection<Any?>
            get() = error("serializer must isolate get per key")

        override fun containsKey(key: String): Boolean = key in keys

        override fun containsValue(value: Any?): Boolean = false

        override fun get(key: String): Any? =
            when (key) {
                "good" -> "first"
                "stillGood" -> "last"
                else -> error("bad value")
            }

        override fun isEmpty(): Boolean = false
    }
}
