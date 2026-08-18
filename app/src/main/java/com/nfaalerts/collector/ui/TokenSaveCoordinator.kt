package com.nfaalerts.collector.ui

import kotlinx.coroutines.CancellationException

enum class TokenSaveOutcome(
    val saved: Boolean,
    val safeWarning: String?,
) {
    Saved(saved = true, safeWarning = null),
    SavedRequeuePending(
        saved = true,
        safeWarning = "Token saved. Delivery requeue is pending; retry from Settings or restart the app.",
    ),
    SavedRequeueFailed(
        saved = true,
        safeWarning = "Token saved, but delivery requeue failed. Re-enter the token or restart the app to retry.",
    ),
    SaveFailed(saved = false, safeWarning = null),
}

internal class TokenSaveCoordinator(
    private val saveBearer: suspend (CharArray) -> Unit,
    private val invalidateVerification: () -> Unit,
    private val refreshBearer: suspend () -> Unit,
    private val requeue: suspend () -> Unit,
) {
    suspend fun save(value: CharArray): TokenSaveOutcome {
        try {
            saveBearer(value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return TokenSaveOutcome.SaveFailed
        }

        try {
            invalidateVerification()
            refreshBearer()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return TokenSaveOutcome.SavedRequeuePending
        }

        return try {
            requeue()
            TokenSaveOutcome.Saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TokenSaveOutcome.SavedRequeueFailed
        }
    }
}
