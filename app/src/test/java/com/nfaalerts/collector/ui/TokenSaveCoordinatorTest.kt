package com.nfaalerts.collector.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenSaveCoordinatorTest {
    @Test
    fun `token input requires one exact 43 character base64url value`() {
        assertEquals("Paste the token before saving.", bearerValidationError(""))
        assertEquals(
            "Token must be exactly 43 characters. Current length: 42.",
            bearerValidationError("a".repeat(42)),
        )
        assertEquals(
            "Token may contain only letters, numbers, hyphens, and underscores.",
            bearerValidationError("a".repeat(42) + "!"),
        )
        assertEquals(null, bearerValidationError("a".repeat(41) + "-_"))
    }

    @Test
    fun `saved token invalidates and refreshes revision before failed requeue`() =
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator =
                TokenSaveCoordinator(
                    saveBearer = { events += "save" },
                    invalidateVerification = { events += "invalidate" },
                    refreshBearer = { events += "refresh" },
                    requeue = {
                        events += "requeue"
                        error("synthetic requeue failure")
                    },
                )

            val outcome = coordinator.save("transient-secret".toCharArray())

            assertEquals(TokenSaveOutcome.SavedRequeueFailed, outcome)
            assertTrue(outcome.saved)
            assertEquals(
                "Token saved, but delivery requeue failed. Re-enter the token or restart the app to retry.",
                outcome.safeWarning,
            )
            assertEquals(listOf("save", "invalidate", "refresh", "requeue"), events)
        }

    @Test
    fun `bearer refresh failure keeps saved outcome and skips stale requeue`() =
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator =
                TokenSaveCoordinator(
                    saveBearer = { events += "save" },
                    invalidateVerification = { events += "invalidate" },
                    refreshBearer = {
                        events += "refresh"
                        error("synthetic refresh failure")
                    },
                    requeue = { events += "requeue" },
                )

            val outcome = coordinator.save("transient-secret".toCharArray())

            assertEquals(TokenSaveOutcome.SavedRequeuePending, outcome)
            assertEquals(listOf("save", "invalidate", "refresh"), events)
        }

    @Test
    fun `bearer save failure remains a save failure without invalidation`() =
        runBlocking {
            val events = mutableListOf<String>()
            val coordinator =
                TokenSaveCoordinator(
                    saveBearer = {
                        events += "save"
                        error("synthetic save failure")
                    },
                    invalidateVerification = { events += "invalidate" },
                    refreshBearer = { events += "refresh" },
                    requeue = { events += "requeue" },
                )

            val outcome = coordinator.save("transient-secret".toCharArray())

            assertEquals(TokenSaveOutcome.SaveFailed, outcome)
            assertTrue(!outcome.saved)
            assertEquals(listOf("save"), events)
        }
}
