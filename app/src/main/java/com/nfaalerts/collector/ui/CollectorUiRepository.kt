package com.nfaalerts.collector.ui

import com.nfaalerts.collector.config.CollectorConfigCodec
import com.nfaalerts.collector.config.ConfigDecodeResult
import com.nfaalerts.collector.config.ConfigValidationError
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.config.SourceSelectionRepository
import com.nfaalerts.collector.ui.settings.SettingsDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CollectorUiRepository {
    val state: StateFlow<CollectorUiSnapshot>

    fun refreshPlatformState()

    suspend fun verify(): Boolean

    suspend fun installedApps(): List<InstalledApp>

    suspend fun selectedSources(): List<SourceSelection>

    fun deliveryRows(): Flow<List<DeliveryUiRow>>

    fun diagnosticRows(): Flow<List<DiagnosticUiRow>>

    suspend fun deliveryEnvelope(eventId: String): String?

    suspend fun exportDiagnostics(): ByteArray

    suspend fun saveToken(value: CharArray): TokenSaveOutcome

    suspend fun saveConfig(payload: String): List<ConfigValidationError>

    suspend fun saveConfigOutcome(payload: String): ConfigMutationOutcome =
        saveConfig(payload).let { errors ->
            if (errors.isEmpty()) ConfigMutationOutcome.Saved else ConfigMutationOutcome.Rejected(errors)
        }

    suspend fun validateConfig(payload: String): List<ConfigValidationError> =
        when (val decoded = CollectorConfigCodec().decode(payload.encodeToByteArray())) {
            is ConfigDecodeResult.Valid -> emptyList()
            is ConfigDecodeResult.Invalid -> decoded.errors
        }

    suspend fun settingsDraft(): SettingsDraft {
        val decoded = CollectorConfigCodec().decode(formattedConfig().encodeToByteArray())
        return SettingsDraft.from((decoded as ConfigDecodeResult.Valid).document)
    }

    suspend fun defaultSettingsDraft(): SettingsDraft = SettingsDraft.from(CollectorConfigCodec().defaultDocument())

    suspend fun saveSettings(draft: SettingsDraft): ConfigMutationOutcome =
        saveConfigOutcome(draft.encodedPayload().decodeToString())

    suspend fun defaultFormattedConfig(): String = CollectorConfigCodec().defaultDocument().root.toString()

    fun configurationFeedback(): StateFlow<String?>? = null

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
