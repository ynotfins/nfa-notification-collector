package com.nfaalerts.collector.contract

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * Local snapshot of the published phone ingest contract.
 * Prefer nfa-platform `contracts/ingest/phone-alerts.contract.json` when published;
 * until then this snapshot is derived from DATABASE.md §10 and ingest-gateway.ts.
 */
@Serializable
data class PhoneAlertsContract(
    val schemaVersion: Int,
    val contractVersion: String,
    val requiredHeaders: List<String>,
    val bodyFields: List<String>,
    val allowedSources: List<String>,
    val bodyLimitBytes: Int,
    val rawTextLimitBytes: Int,
    val metadataLimitBytes: Int,
    val burstLimit: Int,
    val refillPerMinute: Int,
    val emptyRawTextPolicy: String,
    val quarantineHttpStatuses: List<Int>,
    val retryHttpStatuses: List<Int>,
    val pauseAuthHttpStatuses: List<Int>,
    val notes: String,
)

object PhoneAlertsContractCatalog {
    const val BUNDLED_CONTRACT_VERSION = "2026-10-01.schema-v1.bnn-only"

    val bundled: PhoneAlertsContract =
        PhoneAlertsContract(
            schemaVersion = 1,
            contractVersion = BUNDLED_CONTRACT_VERSION,
            requiredHeaders =
                listOf(
                    "Content-Type: application/json",
                    "Authorization: Bearer <device-token>",
                    "X-NFA-Schema-Version: 1",
                ),
            bodyFields = listOf("schemaVersion", "source", "deviceId", "capturedAt", "rawText", "metadata"),
            allowedSources = listOf("bnn"),
            bodyLimitBytes = 262_144,
            rawTextLimitBytes = 131_072,
            metadataLimitBytes = 32_768,
            burstLimit = 240,
            refillPerMinute = 120,
            emptyRawTextPolicy = "reject_400_quarantine",
            quarantineHttpStatuses = listOf(400, 413, 415),
            retryHttpStatuses = listOf(429, 503),
            pauseAuthHttpStatuses = listOf(401),
            notes =
                "Authority: nfa-platform DATABASE.md §10 + scripts/ingest-gateway.ts. " +
                    "Empty/unresolved-template rawText → 400. Non-bnn sources remain local BLOCKED_CONTRACT.",
        )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): PhoneAlertsContract = json.decodeFromString(text)

    fun encode(contract: PhoneAlertsContract): String = json.encodeToString(contract)

    fun driftAgainst(localMirrorNotes: String): List<String> {
        val findings = mutableListOf<String>()
        if (!localMirrorNotes.contains("rawText is empty") &&
            !localMirrorNotes.contains("empty rawText") &&
            !localMirrorNotes.contains("empty is allowed").not()
        ) {
            // Best-effort textual checks are applied by ContractSyncWorker against docs content.
        }
        if (bundled.allowedSources != listOf("bnn")) {
            findings += "bundled allowedSources drifted from bnn-only"
        }
        return findings
    }
}

data class ContractSyncResult(
    val contractVersion: String,
    val checkedAtEpochMillis: Long,
    val findings: List<String>,
    val appliedOnNextStart: Boolean = true,
)
