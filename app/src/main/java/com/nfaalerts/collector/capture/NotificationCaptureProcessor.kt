package com.nfaalerts.collector.capture

import android.app.Notification
import android.content.pm.PackageManager
import com.nfaalerts.collector.config.applicationInfoCompat
import com.nfaalerts.collector.data.CaptureWriteDao
import com.nfaalerts.collector.data.CapturedNotificationEntity
import com.nfaalerts.collector.data.DeliveryOutboxEntity
import com.nfaalerts.collector.data.DeliveryState
import com.nfaalerts.collector.data.InitialDeliveryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class NotificationCaptureProcessor(
    private val packageManager: PackageManager,
    private val captureWriteDao: () -> CaptureWriteDao,
    private val serializer: SafeCanonicalSerializer = SafeCanonicalSerializer(),
) {
    suspend fun process(request: DispatchedNotification) {
        val notification = request.notificationHandle as Notification
        val notificationSnapshot = AndroidNotificationReader.read(notification, request.source.rawTextOrder)
        val applicationInfo =
            runCatching {
                packageManager.applicationInfoCompat(
                    request.identity.packageName,
                )
            }.getOrNull()
        val identity =
            EnvelopeIdentity(
                eventId = request.eventId,
                packageName = request.identity.packageName,
                notificationKey = request.identity.key,
                notificationId = request.identity.notificationId,
                postTimeEpochMillis = request.identity.postTimeEpochMillis,
            )
        val envelope =
            serializer.serialize(
                value =
                    mapOf(
                        "application" to
                            mapOf(
                                "label" to applicationLabel(request.identity.packageName),
                                "packageName" to request.identity.packageName,
                                "uid" to (applicationInfo?.uid ?: request.identity.uid),
                            ),
                        "capturedAtEpochMillis" to request.capturedAtEpochMillis,
                        "clientEventId" to request.eventId,
                        "notification" to notificationSnapshot.envelopeValues,
                        "rawCandidates" to notificationSnapshot.rawTextSelection.candidates,
                        "rawTextSelectedField" to notificationSnapshot.rawTextSelection.selectedField?.configValue,
                        "source" to request.source.sourceId,
                        "statusBarNotification" to identityMap(request.identity),
                    ),
                identity = identity,
            )
        val envelopeJson =
            when (envelope) {
                is CanonicalSerialization.Success -> envelope.json
                is CanonicalSerialization.LimitExceeded -> envelope.minimalEnvelopeJson
            }
        val state =
            if (envelope is CanonicalSerialization.LimitExceeded) {
                DeliveryState.QUARANTINED
            } else {
                InitialDeliveryState.forSource(request.source.sourceId)
            }
        val errorCode =
            if (envelope is CanonicalSerialization.LimitExceeded) LOCAL_ENVELOPE_LIMIT else null
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
                rawText = notificationSnapshot.rawTextSelection.rawText,
                rawCandidatesJson = rawCandidatesJson(notificationSnapshot.rawTextSelection),
                envelopeJson = envelopeJson,
                envelopeSha256 = sha256(envelopeJson),
                envelopeUtf8Bytes = envelopeJson.toByteArray(StandardCharsets.UTF_8).size,
            )
        captureWriteDao().insertCapture(
            capture,
            DeliveryOutboxEntity(
                eventId = request.eventId,
                state = state,
                lastErrorCode = errorCode,
                createdAtEpochMillis = request.capturedAtEpochMillis,
                updatedAtEpochMillis = request.capturedAtEpochMillis,
            ),
        )
    }

    private fun applicationLabel(packageName: String): String? =
        runCatching {
            val info = packageManager.applicationInfoCompat(packageName)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()

    private fun identityMap(identity: NotificationIdentity): Map<String, Any?> =
        mapOf(
            "clearable" to identity.isClearable,
            "id" to identity.notificationId,
            "key" to identity.key,
            "ongoing" to identity.isOngoing,
            "packageName" to identity.packageName,
            "postTimeEpochMillis" to identity.postTimeEpochMillis,
            "tag" to identity.tag,
            "userId" to identity.userId,
        )

    private fun rawCandidatesJson(selection: RawTextSelection): String =
        JsonObject(
            selection.candidates
                .toSortedMap(compareBy(RawTextField::configValue))
                .mapKeys { it.key.configValue }
                .mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) },
        ).toString()

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val LOCAL_ENVELOPE_LIMIT = "LOCAL_ENVELOPE_LIMIT"
    }
}

class CoroutineCaptureDispatcher(
    private val scope: CoroutineScope,
    private val processor: suspend (DispatchedNotification) -> Unit,
) : CaptureDispatcher {
    override fun dispatch(request: DispatchedNotification) {
        scope.launch { processor(request) }
    }
}
