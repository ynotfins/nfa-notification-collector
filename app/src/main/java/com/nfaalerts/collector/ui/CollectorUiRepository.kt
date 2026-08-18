package com.nfaalerts.collector.ui

import com.nfaalerts.collector.config.ConfigValidationError
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.config.SourceSelectionRepository

interface CollectorUiRepository {
    suspend fun snapshot(): CollectorUiSnapshot

    suspend fun installedApps(): List<InstalledApp>

    suspend fun selectedSources(): List<SourceSelection>

    suspend fun deliveryRows(): List<DeliveryUiRow>

    suspend fun deliveryEnvelope(eventId: String): String?

    suspend fun saveToken(value: CharArray): Boolean

    suspend fun saveConfig(payload: String): List<ConfigValidationError>

    suspend fun exportConfig(): ByteArray

    suspend fun importConfig(payload: ByteArray): List<ConfigValidationError>

    suspend fun formattedConfig(): String

    suspend fun retry(eventId: String): Boolean
}

interface SourcePickerUiAccess {
    val sourceSelectionRepository: SourceSelectionRepository

    suspend fun sourcePickerApps(): List<InstalledApp>

    fun sourceIdForPackage(packageName: String): String
}
