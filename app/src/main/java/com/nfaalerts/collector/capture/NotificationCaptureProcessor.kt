package com.nfaalerts.collector.capture

import android.app.Notification
import android.content.pm.PackageManager
import com.nfaalerts.collector.config.applicationInfoCompat
import com.nfaalerts.collector.data.CapturedNotificationEntity
import com.nfaalerts.collector.data.DeliveryOutboxEntity
import com.nfaalerts.collector.data.DeliveryState
import com.nfaalerts.collector.data.InitialDeliveryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

fun interface NotificationContentReader {
    fun read(
        notificationHandle: Any,
        rawTextOrder: List<RawTextField>,
    ): AndroidNotificationSnapshot
}

data class ApplicationMetadata(
    val label: String?,
    val uid: Int,
)

fun interface ApplicationMetadataResolver {
    fun resolve(
        packageName: String,
        fallbackUid: Int,
    ): ApplicationMetadata
}

fun interface CapturePersistence {
    suspend fun persist(
        capture: CapturedNotificationEntity,
        outbox: DeliveryOutboxEntity,
    )
}

class NotificationCaptureProcessor(
    private val reader: NotificationContentReader,
    private val applicationMetadataResolver: ApplicationMetadataResolver,
    private val persistence: CapturePersistence,
    private val serializer: SafeCanonicalSerializer = SafeCanonicalSerializer(),
    private val onPersisted: suspend (String) -> Unit = {},
) {
    suspend fun process(request: DispatchedNotification) {
        val rows =
            try {
                buildRows(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failureRows(request, CAPTURE_PROCESSING_FAILURE, failure.javaClass.name)
            }
        try {
            persistence.persist(rows.first, rows.second)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val fallback = failureRows(request, CAPTURE_PROCESSING_FAILURE, failure.javaClass.name)
            persistence.persist(fallback.first, fallback.second)
        }
        try {
            onPersisted(request.eventId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The immutable Room row is already durable. Worker recovery remains authoritative.
        }
    }

    private fun buildRows(request: DispatchedNotification): Pair<CapturedNotificationEntity, DeliveryOutboxEntity> {
        val snapshot = reader.read(request.notificationHandle, request.source.rawTextOrder)
        val application = applicationMetadataResolver.resolve(request.identity.packageName, request.identity.uid)
        val envelope =
            serializer.serialize(
                mapOf(
                    "application" to
                        mapOf(
                            "label" to application.label,
                            "packageName" to request.identity.packageName,
                            "uid" to application.uid,
                        ),
                    "capturedAtEpochMillis" to request.capturedAtEpochMillis,
                    "clientEventId" to request.eventId,
                    "notification" to snapshot.envelopeValues,
                    "rawCandidates" to snapshot.rawTextSelection.candidates,
                    "rawTextSelectedField" to snapshot.rawTextSelection.selectedField?.configValue,
                    "source" to request.source.sourceId,
                    "statusBarNotification" to identityMap(request.identity),
                ),
                envelopeIdentity(request),
            )
        return when (envelope) {
            is CanonicalSerialization.Success -> {
                rows(
                    request,
                    envelope.json,
                    InitialDeliveryState.forSource(request.source.sourceId),
                    null,
                    snapshot.rawTextSelection.rawText,
                    rawCandidatesJson(snapshot.rawTextSelection),
                )
            }

            is CanonicalSerialization.LimitExceeded -> {
                rows(
                    request,
                    envelope.minimalEnvelopeJson,
                    DeliveryState.QUARANTINED,
                    LOCAL_ENVELOPE_LIMIT,
                    null,
                    EMPTY_JSON_OBJECT,
                )
            }

            is CanonicalSerialization.Failure -> {
                rows(
                    request,
                    envelope.minimalEnvelopeJson,
                    DeliveryState.QUARANTINED,
                    SERIALIZATION_FAILURE,
                    null,
                    EMPTY_JSON_OBJECT,
                )
            }
        }
    }

    private fun failureRows(
        request: DispatchedNotification,
        errorCode: String,
        failureType: String,
    ) = rows(
        request,
        minimalFailureEnvelope(request, errorCode, failureType),
        DeliveryState.QUARANTINED,
        errorCode,
        null,
        EMPTY_JSON_OBJECT,
    )

    private fun rows(
        request: DispatchedNotification,
        envelopeJson: String,
        state: DeliveryState,
        errorCode: String?,
        rawText: String?,
        rawCandidatesJson: String,
    ): Pair<CapturedNotificationEntity, DeliveryOutboxEntity> {
        val capture =
            CapturedNotificationEntity(
                eventId = request.eventId,
                packageName = request.identity.packageName,
                sourceId = request.source.sourceId,
                notificationKey = request.identity.key,
                notificationId = request.identity.notificationId,
                notificationTag = request.identity.tag,
                postTimeEpochMillis = request.identity.postTimeEpochMillis,
                capturedAtEpochMillis = request.capturedAtEpochMillis,
                rawText = rawText,
                rawCandidatesJson = rawCandidatesJson,
                envelopeJson = envelopeJson,
                envelopeSha256 = sha256(envelopeJson),
                envelopeUtf8Bytes = envelopeJson.toByteArray(StandardCharsets.UTF_8).size,
            )
        return capture to
            DeliveryOutboxEntity(
                eventId = request.eventId,
                state = state,
                lastErrorCode = errorCode,
                createdAtEpochMillis = request.capturedAtEpochMillis,
                updatedAtEpochMillis = request.capturedAtEpochMillis,
            )
    }

    private fun identityMap(identity: NotificationIdentity): Map<String, Any?> =
        mapOf(
            "clearable" to identity.isClearable,
            "groupKey" to identity.groupKey,
            "id" to identity.notificationId,
            "key" to identity.key,
            "ongoing" to identity.isOngoing,
            "overrideGroupKey" to identity.overrideGroupKey,
            "packageName" to identity.packageName,
            "postTimeEpochMillis" to identity.postTimeEpochMillis,
            "tag" to identity.tag,
            "userId" to identity.userId,
        )

    private fun envelopeIdentity(request: DispatchedNotification) =
        EnvelopeIdentity(
            request.eventId,
            request.identity.packageName,
            request.identity.key,
            request.identity.notificationId,
            request.identity.postTimeEpochMillis,
        )

    private fun rawCandidatesJson(selection: RawTextSelection): String =
        JsonObject(
            selection.candidates
                .toSortedMap(
                    compareBy(RawTextField::configValue),
                ).mapKeys { it.key.configValue }
                .mapValues { (_, values) ->
                    JsonArray(values.map(::JsonPrimitive))
                },
        ).toString()

    private fun minimalFailureEnvelope(
        request: DispatchedNotification,
        errorCode: String,
        failureType: String,
    ): String =
        JsonObject(
            sortedMapOf(
                "errorCode" to JsonPrimitive(errorCode),
                "eventId" to JsonPrimitive(request.eventId.take(128)),
                "failureType" to JsonPrimitive(failureType.take(512)),
                "identity" to
                    JsonObject(
                        sortedMapOf(
                            "notificationId" to JsonPrimitive(request.identity.notificationId),
                            "notificationKey" to boundedJsonString(request.identity.key),
                            "packageName" to boundedJsonString(request.identity.packageName),
                            "postTimeEpochMillis" to JsonPrimitive(request.identity.postTimeEpochMillis),
                            "tag" to (request.identity.tag?.let(::boundedJsonString) ?: JsonNull),
                        ),
                    ),
                "quarantined" to JsonPrimitive(true),
            ),
        ).toString()

    private fun boundedJsonString(value: String) = JsonPrimitive(value.take(MAX_IDENTITY_CHARS))

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") {
            "%02x".format(it)
        }

    companion object {
        private const val EMPTY_JSON_OBJECT = "{}"
        private const val MAX_IDENTITY_CHARS = 2_048
        const val LOCAL_ENVELOPE_LIMIT = "LOCAL_ENVELOPE_LIMIT"
        const val CAPTURE_PROCESSING_FAILURE = "CAPTURE_PROCESSING_FAILURE"
        const val SERIALIZATION_FAILURE = "SERIALIZATION_FAILURE"

        fun createAndroid(
            packageManager: PackageManager,
            captureWriteDao: () -> com.nfaalerts.collector.data.CaptureWriteDao,
            onPersisted: suspend (String) -> Unit = {},
        ) = NotificationCaptureProcessor(
            reader =
                NotificationContentReader {
                    handle,
                    order,
                    ->
                    AndroidNotificationReader.read(handle as Notification, order)
                },
            applicationMetadataResolver =
                ApplicationMetadataResolver { packageName, fallbackUid ->
                    val info = runCatching { packageManager.applicationInfoCompat(packageName) }.getOrNull()
                    ApplicationMetadata(
                        info?.let { packageManager.getApplicationLabel(it).toString() },
                        info?.uid ?: fallbackUid,
                    )
                },
            persistence =
                CapturePersistence {
                    capture,
                    outbox,
                    ->
                    captureWriteDao().insertCapture(capture, outbox)
                },
            onPersisted = onPersisted,
        )
    }
}

class CoroutineCaptureDispatcher(
    private val scope: CoroutineScope,
    private val processor: suspend (DispatchedNotification) -> Unit,
    private val diagnostics: CaptureDispatchDiagnostics,
) : CaptureDispatcher {
    override fun dispatch(request: DispatchedNotification) {
        if (!scope.isActive) {
            diagnostics.onDispatchRejected()
            return
        }
        scope.launch {
            diagnostics.onDispatchStarted()
            try {
                processor(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                diagnostics.onWorkerFailure(failure.javaClass.name)
            } finally {
                diagnostics.onDispatchFinished()
            }
        }
    }
}
