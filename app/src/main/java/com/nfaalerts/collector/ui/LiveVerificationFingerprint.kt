package com.nfaalerts.collector.ui

import com.nfaalerts.collector.config.SourceSelection
import java.nio.ByteBuffer
import java.security.MessageDigest

class LiveVerificationFingerprint private constructor(
    private val digest: String,
) {
    override fun equals(other: Any?): Boolean = other is LiveVerificationFingerprint && digest == other.digest

    override fun hashCode(): Int = digest.hashCode()

    companion object {
        fun create(
            canonicalConfigHash: String,
            activeProfileId: String,
            baseUrl: String,
            ingestPath: String,
            deviceId: String,
            bearerRevisionFingerprint: Long,
            sources: List<SourceSelection>,
            notificationAccess: NotificationAccessState,
            connectivity: ConnectivityState,
        ): LiveVerificationFingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.add(canonicalConfigHash)
            digest.add(activeProfileId)
            digest.add(baseUrl)
            digest.add(ingestPath)
            digest.add(deviceId)
            digest.add(bearerRevisionFingerprint.toString())
            digest.add(notificationAccess.name)
            digest.add(connectivity.name)
            val enabledSources =
                sources
                    .asSequence()
                    .filter(SourceSelection::enabled)
                    .sortedBy(SourceSelection::packageName)
                    .toList()
            digest.add(enabledSources.size.toString())
            enabledSources.forEach { source ->
                digest.add(source.packageName)
                digest.add(source.sourceId)
                digest.add(source.bnnMappingConfirmed.toString())
                digest.add(source.rawTextOrder.size.toString())
                source.rawTextOrder.forEach { digest.add(it.configValue) }
            }
            return LiveVerificationFingerprint(digest.digest().toHex())
        }

        private fun MessageDigest.add(value: String) {
            val bytes = value.encodeToByteArray()
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            update(bytes)
            bytes.fill(0)
        }
    }
}

internal fun canonicalConfigHash(payload: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload)
    return try {
        digest.toHex()
    } finally {
        digest.fill(0)
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { "%02x".format(it) }
