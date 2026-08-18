package com.nfaalerts.collector.ui

import com.nfaalerts.collector.capture.RawTextField
import com.nfaalerts.collector.config.SourceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveVerificationFingerprintTest {
    @Test
    fun `canonical config profile and endpoint swaps invalidate prior verification`() {
        val source = source("com.example.bnn", listOf(RawTextField.BIG_TEXT, RawTextField.TEXT))
        val base = fingerprint(sources = listOf(source))

        assertNotEquals(base, fingerprint(configHash = "config-b", sources = listOf(source)))
        assertNotEquals(base, fingerprint(profile = "backup", sources = listOf(source)))
        assertNotEquals(base, fingerprint(baseUrl = "https://other.example", sources = listOf(source)))
        assertNotEquals(base, fingerprint(path = "/v1/ingest/other", sources = listOf(source)))
        assertNotEquals(base, fingerprint(deviceId = "other-device", sources = listOf(source)))
    }

    @Test
    fun `token revision swap invalidates prior verification`() {
        val source = source("com.example.bnn", listOf(RawTextField.TEXT))
        val base = fingerprint(sources = listOf(source))

        assertNotEquals(base, fingerprint(bearerRevision = 12L, sources = listOf(source)))
    }

    @Test
    fun `source mapping confirmation and raw priority swaps invalidate prior verification`() {
        val source = source("com.example.bnn", listOf(RawTextField.BIG_TEXT, RawTextField.TEXT))
        val base = fingerprint(sources = listOf(source))

        assertNotEquals(base, fingerprint(sources = listOf(source.copy(sourceId = "local"))))
        assertNotEquals(base, fingerprint(sources = listOf(source.copy(bnnMappingConfirmed = false))))
        assertNotEquals(base, fingerprint(sources = listOf(source.copy(rawTextOrder = source.rawTextOrder.reversed()))))
    }

    @Test
    fun `access and connectivity swaps invalidate prior verification`() {
        val source = source("com.example.bnn", listOf(RawTextField.TEXT))
        val base = fingerprint(sources = listOf(source))

        assertNotEquals(base, fingerprint(access = NotificationAccessState.Required, sources = listOf(source)))
        assertNotEquals(base, fingerprint(connectivity = ConnectivityState.Disconnected, sources = listOf(source)))
    }

    @Test
    fun `enabled source fingerprint is deterministic and excludes disabled sources`() {
        val first = source("com.example.first", listOf(RawTextField.TEXT))
        val second = source("com.example.second", listOf(RawTextField.TICKER))
        val disabled = source("com.example.disabled", listOf(RawTextField.BIG_TEXT)).copy(enabled = false)

        assertEquals(
            fingerprint(sources = listOf(first, second)),
            fingerprint(sources = listOf(second, disabled, first)),
        )
    }

    private fun fingerprint(
        configHash: String = "config-a",
        profile: String = "primary",
        baseUrl: String = "https://example.invalid",
        path: String = "/v1/ingest/alerts",
        deviceId: String = "device-a",
        bearerRevision: Long = 11L,
        sources: List<SourceSelection>,
        access: NotificationAccessState = NotificationAccessState.Granted,
        connectivity: ConnectivityState = ConnectivityState.Connected,
    ) = LiveVerificationFingerprint.create(
        canonicalConfigHash = configHash,
        activeProfileId = profile,
        baseUrl = baseUrl,
        ingestPath = path,
        deviceId = deviceId,
        bearerRevisionFingerprint = bearerRevision,
        sources = sources,
        notificationAccess = access,
        connectivity = connectivity,
    )

    private fun source(
        packageName: String,
        rawTextOrder: List<RawTextField>,
    ) = SourceSelection(
        packageName = packageName,
        appLabel = "Display label is not identity",
        sourceId = "bnn",
        enabled = true,
        bnnMappingConfirmed = true,
        rawTextOrder = rawTextOrder,
    )
}
