package com.nfaalerts.collector.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContractSyncCoordinatorTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun marksPublishedContractPresentAndStampsBundledVersion() {
        val filesDir = temp.newFolder("files")
        val coordinator =
            ContractSyncCoordinator(
                filesDir = filesDir,
                clock = { 1_000L },
                localIngestContractText = {
                    """
                    Authenticated rate burst | 240
                    Refill | 120/minute/device
                    source is currently exactly `bnn`
                    Empty / whitespace-only is invalid
                    """.trimIndent()
                },
            )

        val result = coordinator.maybeCheck(force = true)
        requireNotNull(result)
        assertEquals(PhoneAlertsContractCatalog.BUNDLED_CONTRACT_VERSION, result.contractVersion)
        assertTrue(File(filesDir, ContractSyncCoordinator.PUBLISHED_MARKER).exists())
        assertFalse(result.findings.any { it.contains("not published yet", ignoreCase = true) })
        assertEquals(PhoneAlertsContractCatalog.BUNDLED_CONTRACT_VERSION, coordinator.activeContractVersion())
    }

    @Test
    fun skipsDocsFindingsWhenAssetTextMissing() {
        val filesDir = temp.newFolder("files")
        val coordinator =
            ContractSyncCoordinator(
                filesDir = filesDir,
                clock = { 2_000L },
                localIngestContractText = { "" },
            )
        val result = requireNotNull(coordinator.maybeCheck(force = true))
        assertTrue(result.findings.none { it.contains("missing published rate limits") })
        assertTrue(result.findings.none { it.contains("missing explicit bnn-only") })
    }
}
