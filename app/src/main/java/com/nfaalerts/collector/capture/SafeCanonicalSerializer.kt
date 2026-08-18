@file:Suppress("DEPRECATION")

package com.nfaalerts.collector.capture

import android.app.PendingIntent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap
import java.lang.reflect.Array as ReflectArray

data class SafeSerializerLimits(
    val maxEnvelopeUtf8Bytes: Int = 2 * 1024 * 1024,
    val maxDepth: Int = 16,
    val maxNodes: Int = 4_096,
    val maxArrayEntries: Int = 1_024,
    val maxStringUtf8Bytes: Int = 1024 * 1024,
)

data class EnvelopeIdentity(
    val eventId: String,
    val packageName: String,
    val notificationKey: String,
    val notificationId: Int,
    val postTimeEpochMillis: Long,
)

sealed interface CanonicalSerialization {
    data class Success(
        val json: String,
        val utf8Bytes: Int,
    ) : CanonicalSerialization

    data class LimitExceeded(
        val limitName: String,
        val path: String,
        val measuredValue: Int,
        val originalSafeType: String,
        val minimalEnvelopeJson: String,
    ) : CanonicalSerialization
}

class SafeCanonicalSerializer(
    private val limits: SafeSerializerLimits = SafeSerializerLimits(),
) {
    fun serialize(
        value: Any?,
        identity: EnvelopeIdentity,
    ): CanonicalSerialization {
        val state = SerializationState()
        return try {
            val json = encode(value, "$", 0, state).toString()
            val utf8Bytes = json.utf8Bytes()
            if (utf8Bytes > limits.maxEnvelopeUtf8Bytes) {
                throw LimitBreach(
                    limitName = "maxEnvelopeUtf8Bytes",
                    path = "$",
                    measuredValue = utf8Bytes,
                    originalSafeType = safeType(value),
                )
            }
            CanonicalSerialization.Success(json, utf8Bytes)
        } catch (breach: LimitBreach) {
            CanonicalSerialization.LimitExceeded(
                limitName = breach.limitName,
                path = breach.path,
                measuredValue = breach.measuredValue,
                originalSafeType = breach.originalSafeType,
                minimalEnvelopeJson = minimalLimitEnvelope(identity, breach).toString(),
            )
        }
    }

    private fun encode(
        value: Any?,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement {
        if (depth > limits.maxDepth) {
            throw LimitBreach("maxDepth", path, depth, safeType(value))
        }
        state.nodes += 1
        if (state.nodes > limits.maxNodes) {
            throw LimitBreach("maxNodes", path, state.nodes, safeType(value))
        }

        return when (value) {
            null -> {
                typed("null", JsonNull)
            }

            is String -> {
                encodeString(value, path)
            }

            is CharSequence -> {
                encodeString(value.toString(), path)
            }

            is Boolean -> {
                typed("boolean", JsonPrimitive(value))
            }

            is Byte, is Short, is Int, is Long -> {
                typed("integer", JsonPrimitive((value as Number).toLong()))
            }

            is Float, is Double -> {
                typed("number", JsonPrimitive((value as Number).toDouble()))
            }

            is Char -> {
                encodeString(value.toString(), path)
            }

            is Enum<*> -> {
                typed("enum", JsonPrimitive(value.name), "runtimeType" to JsonPrimitive(value.javaClass.name))
            }

            is Bundle -> {
                encodeBundle(value, path, depth, state)
            }

            is Map<*, *> -> {
                encodeMap(value, path, depth, state)
            }

            is Iterable<*> -> {
                encodeIterable(value, path, depth, state)
            }

            is SafeOpaqueValue -> {
                encodeOpaque(value, path, depth, state)
            }

            is SafeActionValue -> {
                encodeAction(value, path, depth, state)
            }

            is SafeMessageValue -> {
                encodeMessage(value, path, depth, state)
            }

            is SafeRemoteInputValue -> {
                encodeRemoteInput(value, path, depth, state)
            }

            is ByteArray -> {
                encodeOpaque(SafeOpaqueValue.binary(value.size), path, depth, state)
            }

            is PendingIntent -> {
                encodeOpaque(SafeOpaqueValue.executable(PendingIntent::class.java.name), path, depth, state)
            }

            is Bitmap -> {
                encodeOpaque(
                    SafeOpaqueValue(
                        safeType = "bitmap",
                        marker = "bitmap_metadata_only",
                        metadata =
                            mapOf(
                                "width" to value.width,
                                "height" to value.height,
                                "config" to value.config?.name,
                            ),
                    ),
                    path,
                    depth,
                    state,
                )
            }

            is Icon -> {
                encodeOpaque(
                    SafeOpaqueValue(
                        safeType = "icon",
                        marker = "icon_metadata_only",
                        metadata =
                            mapOf(
                                "iconType" to
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                        value.type
                                    } else {
                                        null
                                    },
                            ),
                    ),
                    path,
                    depth,
                    state,
                )
            }

            is Uri -> {
                typed("uri", JsonPrimitive(value.toString()))
            }

            else -> {
                if (value.javaClass.isArray) {
                    encodeReflectiveArray(value, path, depth, state)
                } else {
                    encodeUnknown(value, path)
                }
            }
        }
    }

    private fun encodeString(
        value: String,
        path: String,
    ): JsonElement {
        val bytes = value.utf8Bytes()
        if (bytes > limits.maxStringUtf8Bytes) {
            throw LimitBreach("maxStringUtf8Bytes", path, bytes, "string")
        }
        return typed("string", JsonPrimitive(value))
    }

    private fun encodeMap(
        value: Map<*, *>,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        composite(value, path, "object", state) {
            val keys =
                value.keys
                    .map { original -> original to (original?.toString() ?: "null") }
                    .sortedWith(compareBy<Pair<Any?, String>> { it.second }.thenBy { it.first?.javaClass?.name })
            val objectValues = linkedMapOf<String, JsonElement>()
            keys.forEach { (originalKey, key) ->
                val childPath = "$path.${escapePath(key)}"
                objectValues[key] =
                    try {
                        encode(value[originalKey], childPath, depth + 1, state)
                    } catch (breach: LimitBreach) {
                        throw breach
                    } catch (failure: Throwable) {
                        failureMarker("per_key_failure", failure)
                    }
            }
            JsonObject(objectValues)
        }

    private fun encodeBundle(
        bundle: Bundle,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        composite(bundle, path, "bundle", state) {
            val values = linkedMapOf<String, JsonElement>()
            val keys =
                runCatching { bundle.keySet().toList().sorted() }.getOrElse {
                    return@composite failureMarker("key_enumeration_failure", it)
                }
            keys.forEach { key ->
                val childPath = "$path.${escapePath(key)}"
                values[key] =
                    try {
                        encode(bundle.get(key), childPath, depth + 1, state)
                    } catch (breach: LimitBreach) {
                        throw breach
                    } catch (failure: Throwable) {
                        failureMarker("per_key_failure", failure)
                    }
            }
            JsonObject(values)
        }

    private fun encodeIterable(
        value: Iterable<*>,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        composite(value, path, "array", state) {
            val iterator = value.iterator()
            val elements = mutableListOf<JsonElement>()
            while (iterator.hasNext()) {
                if (elements.size >= limits.maxArrayEntries) {
                    throw LimitBreach("maxArrayEntries", path, elements.size + 1, "array")
                }
                elements += encode(iterator.next(), "$path[${elements.size}]", depth + 1, state)
            }
            JsonArray(elements)
        }

    private fun encodeReflectiveArray(
        value: Any,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        composite(value, path, "array", state) {
            val length = ReflectArray.getLength(value)
            if (length > limits.maxArrayEntries) {
                throw LimitBreach("maxArrayEntries", path, length, "array")
            }
            JsonArray(
                (0 until length).map { index ->
                    encode(ReflectArray.get(value, index), "$path[$index]", depth + 1, state)
                },
            )
        }

    private fun encodeOpaque(
        value: SafeOpaqueValue,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        encodeMap(
            mapOf(
                "marker" to value.marker,
                "metadata" to value.metadata,
                "safeType" to value.safeType,
            ),
            path,
            depth,
            state,
        )

    private fun encodeAction(
        value: SafeActionValue,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        encodeMap(
            mapOf(
                "authenticationRequired" to value.authenticationRequired,
                "pendingIntent" to
                    if (value.hasPendingIntent) {
                        SafeOpaqueValue("pendingIntent", "pending_intent_omitted", emptyMap())
                    } else {
                        null
                    },
                "remoteInputs" to value.remoteInputs,
                "semanticAction" to value.semanticAction,
                "showsUserInterface" to value.showsUserInterface,
                "title" to value.title,
            ),
            path,
            depth,
            state,
        )

    private fun encodeMessage(
        value: SafeMessageValue,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        encodeMap(
            mapOf(
                "sender" to value.sender,
                "text" to value.text,
                "timestampEpochMillis" to value.timestampEpochMillis,
            ),
            path,
            depth,
            state,
        )

    private fun encodeRemoteInput(
        value: SafeRemoteInputValue,
        path: String,
        depth: Int,
        state: SerializationState,
    ): JsonElement =
        encodeMap(
            mapOf(
                "allowFreeFormInput" to value.allowFreeFormInput,
                "allowedDataTypes" to value.allowedDataTypes.sorted(),
                "label" to value.label,
                "resultKey" to value.resultKey,
            ),
            path,
            depth,
            state,
        )

    private fun encodeUnknown(
        value: Any,
        path: String,
    ): JsonElement {
        val representation = runCatching { value.toString() }
        return if (representation.isSuccess) {
            val text = representation.getOrThrow()
            if (text.utf8Bytes() > limits.maxStringUtf8Bytes) {
                throw LimitBreach("maxStringUtf8Bytes", path, text.utf8Bytes(), "unknown")
            }
            JsonObject(
                sortedMapOf(
                    "representation" to JsonPrimitive(text),
                    "runtimeType" to JsonPrimitive(value.javaClass.name),
                    "type" to JsonPrimitive("unknown"),
                ),
            )
        } else {
            JsonObject(
                sortedMapOf(
                    "marker" to JsonPrimitive("representation_failure"),
                    "runtimeType" to JsonPrimitive(value.javaClass.name),
                    "type" to JsonPrimitive("unknown"),
                ),
            )
        }
    }

    private fun composite(
        value: Any,
        path: String,
        type: String,
        state: SerializationState,
        block: () -> JsonElement,
    ): JsonElement {
        if (state.active.put(value, path) != null) {
            return JsonObject(
                sortedMapOf(
                    "marker" to JsonPrimitive("cycle"),
                    "type" to JsonPrimitive("cycle"),
                ),
            )
        }
        return try {
            typed(type, block())
        } finally {
            state.active.remove(value)
        }
    }

    private fun typed(
        type: String,
        value: JsonElement,
        vararg extra: Pair<String, JsonElement>,
    ): JsonObject =
        JsonObject(
            sortedMapOf<String, JsonElement>().apply {
                putAll(extra)
                put("type", JsonPrimitive(type))
                put("value", value)
            },
        )

    private fun failureMarker(
        marker: String,
        failure: Throwable,
    ): JsonObject =
        JsonObject(
            sortedMapOf(
                "failureType" to JsonPrimitive(failure.javaClass.name),
                "marker" to JsonPrimitive(marker),
                "type" to JsonPrimitive("error"),
            ),
        )

    private fun minimalLimitEnvelope(
        identity: EnvelopeIdentity,
        breach: LimitBreach,
    ): JsonObject =
        JsonObject(
            sortedMapOf(
                "eventId" to JsonPrimitive(identity.eventId),
                "identity" to
                    JsonObject(
                        sortedMapOf(
                            "notificationId" to JsonPrimitive(identity.notificationId),
                            "notificationKey" to JsonPrimitive(identity.notificationKey),
                            "packageName" to JsonPrimitive(identity.packageName),
                            "postTimeEpochMillis" to JsonPrimitive(identity.postTimeEpochMillis),
                        ),
                    ),
                "limitEnvelope" to JsonPrimitive(true),
                "limitName" to JsonPrimitive(breach.limitName),
                "measuredValue" to JsonPrimitive(breach.measuredValue),
                "originalSafeType" to JsonPrimitive(breach.originalSafeType),
                "path" to JsonPrimitive(breach.path),
            ),
        )

    private fun safeType(value: Any?): String =
        when (value) {
            null -> "null"
            is String, is CharSequence -> "string"
            is Map<*, *>, is Bundle -> "object"
            is Iterable<*> -> "array"
            else -> value.javaClass.name
        }

    private fun escapePath(key: String): String = key.replace("\\", "\\\\").replace(".", "\\.")

    private fun String.utf8Bytes(): Int = toByteArray(StandardCharsets.UTF_8).size

    private class SerializationState {
        var nodes: Int = 0
        val active = IdentityHashMap<Any, String>()
    }

    private data class LimitBreach(
        val limitName: String,
        val path: String,
        val measuredValue: Int,
        val originalSafeType: String,
    ) : RuntimeException()
}
