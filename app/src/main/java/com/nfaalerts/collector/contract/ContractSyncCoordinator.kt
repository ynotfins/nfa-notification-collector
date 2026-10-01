package com.nfaalerts.collector.contract

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Idle-friendly daily contract check. Does not hot-swap mid-send.
 * New contractVersion is observed on next app start / next daily tick; in-flight
 * outbox rows keep the contractVersion stamped at insert time.
 */
class ContractSyncCoordinator(
    private val filesDir: File,
    private val bundled: PhoneAlertsContract = PhoneAlertsContractCatalog.bundled,
    private val clock: () -> Long = System::currentTimeMillis,
    private val localIngestContractText: () -> String = { "" },
) {
    private val stateFile = File(filesDir, STATE_FILE)

    fun maybeCheck(force: Boolean = false): ContractSyncResult? {
        val previous = readState()
        val now = clock()
        if (!force && previous != null && now - previous.checkedAtEpochMillis < DAY_MS) {
            return null
        }
        val findings = mutableListOf<String>()
        val docs = localIngestContractText()
        if (docs.contains("empty is allowed", ignoreCase = true)) {
            findings += "docs/INGEST-CONTRACT.md still claims empty rawText is allowed; gateway rejects empty with 400"
        }
        if (!docs.contains("240") || !docs.contains("120")) {
            findings += "docs/INGEST-CONTRACT.md missing published rate limits 240 burst / 120 per minute"
        }
        if (!docs.contains("\"source\": \"bnn\"") && !docs.contains("source is currently exactly `bnn`")) {
            findings += "docs/INGEST-CONTRACT.md missing explicit bnn-only source rule"
        }
        val publishedMarker = File(filesDir, PUBLISHED_MARKER)
        if (!publishedMarker.exists()) {
            findings +=
                "nfa-platform contracts/ingest/phone-alerts.contract.json not published yet; " +
                    "using bundled snapshot ${bundled.contractVersion}"
        }
        val result =
            ContractSyncResult(
                contractVersion = bundled.contractVersion,
                checkedAtEpochMillis = now,
                findings = findings,
            )
        writeState(result)
        return result
    }

    fun activeContractVersion(): String = readState()?.contractVersion ?: bundled.contractVersion

    private fun readState(): ContractSyncResult? {
        if (!stateFile.exists()) return null
        return runCatching {
            val lines = stateFile.readLines()
            ContractSyncResult(
                contractVersion = lines.getOrNull(0) ?: bundled.contractVersion,
                checkedAtEpochMillis = lines.getOrNull(1)?.toLongOrNull() ?: 0L,
                findings = lines.drop(2).filter(String::isNotBlank),
            )
        }.getOrNull()
    }

    private fun writeState(result: ContractSyncResult) {
        stateFile.parentFile?.mkdirs()
        stateFile.writeText(
            buildString {
                appendLine(result.contractVersion)
                appendLine(result.checkedAtEpochMillis)
                result.findings.forEach { appendLine(it) }
            },
        )
    }

    companion object {
        private const val STATE_FILE = "contract-sync-state.txt"
        private const val PUBLISHED_MARKER = "phone-alerts.contract.present"
        private val DAY_MS = TimeUnit.DAYS.toMillis(1)

        fun create(context: Context, ingestContractText: () -> String) =
            ContractSyncCoordinator(
                filesDir = context.filesDir,
                localIngestContractText = ingestContractText,
            )

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
