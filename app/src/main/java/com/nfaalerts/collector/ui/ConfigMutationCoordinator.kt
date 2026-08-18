package com.nfaalerts.collector.ui

import com.nfaalerts.collector.config.ConfigSaveResult
import com.nfaalerts.collector.config.ConfigValidationError

sealed interface ConfigMutationOutcome {
    data object Saved : ConfigMutationOutcome

    data class SavedFollowUpPending(
        val code: String,
    ) : ConfigMutationOutcome

    data class Rejected(
        val errors: List<ConfigValidationError>,
    ) : ConfigMutationOutcome

    data object SaveFailed : ConfigMutationOutcome
}

class ConfigMutationCoordinator(
    private val invalidateVerification: () -> Unit,
    private val reloadSources: suspend () -> Unit,
    private val reloadUi: suspend () -> Unit,
    private val requeue: suspend () -> Unit,
    private val onPersist: () -> Unit = {},
) {
    suspend fun save(persist: suspend () -> ConfigSaveResult): ConfigMutationOutcome {
        onPersist()
        return when (val result = runCatching { persist() }.getOrElse { return ConfigMutationOutcome.SaveFailed }) {
            is ConfigSaveResult.Rejected -> ConfigMutationOutcome.Rejected(result.errors)
            ConfigSaveResult.IoFailure -> ConfigMutationOutcome.SaveFailed
            is ConfigSaveResult.Saved -> afterSaved()
        }
    }

    private suspend fun afterSaved(): ConfigMutationOutcome {
        invalidateVerification()
        runCatching { reloadSources() }
            .getOrElse { return ConfigMutationOutcome.SavedFollowUpPending("SOURCE_RELOAD_PENDING") }
        runCatching { reloadUi() }
            .getOrElse { return ConfigMutationOutcome.SavedFollowUpPending("UI_RELOAD_PENDING") }
        runCatching { requeue() }
            .getOrElse { return ConfigMutationOutcome.SavedFollowUpPending("DELIVERY_REQUEUE_PENDING") }
        return ConfigMutationOutcome.Saved
    }
}
