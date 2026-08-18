package com.nfaalerts.collector.ui

import com.nfaalerts.collector.config.ConfigSaveResult
import com.nfaalerts.collector.config.ConfigValidationError
import kotlinx.coroutines.CancellationException

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
        val result =
            try {
                persist()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return ConfigMutationOutcome.SaveFailed
            }
        return when (result) {
            is ConfigSaveResult.Rejected -> ConfigMutationOutcome.Rejected(result.errors)
            ConfigSaveResult.IoFailure -> ConfigMutationOutcome.SaveFailed
            is ConfigSaveResult.Saved -> afterSaved()
        }
    }

    private suspend fun afterSaved(): ConfigMutationOutcome {
        invalidateVerification()
        followUp("SOURCE_RELOAD_PENDING", reloadSources)?.let { return it }
        followUp("UI_RELOAD_PENDING", reloadUi)?.let { return it }
        followUp("DELIVERY_REQUEUE_PENDING", requeue)?.let { return it }
        return ConfigMutationOutcome.Saved
    }

    private suspend fun followUp(
        code: String,
        block: suspend () -> Unit,
    ): ConfigMutationOutcome.SavedFollowUpPending? =
        try {
            block()
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            ConfigMutationOutcome.SavedFollowUpPending(code)
        }
}
