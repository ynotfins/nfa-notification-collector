package com.nfaalerts.collector.capture

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nfaalerts.collector.NfaCollectorApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual live-proof helper. Logs only delivery metadata (package, source,
 * state, HTTP status, server ingest id, timestamps) and safe diagnostic
 * details. Never logs notification text or the bearer.
 */
@RunWith(AndroidJUnit4::class)
class LiveCaptureProofInstrumentedTest {
    @Test
    fun printRecentBnnDeliveryFacts() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<NfaCollectorApp>()
            val container = app.appContainer
            val rows = container.recentDeliveryInspection().filter { it.packageName == BNN_PACKAGE }
            Log.i(TAG, "bnnRows=${rows.size}")
            rows.take(8).forEach { row ->
                Log.i(
                    TAG,
                    "event=${row.eventId} source=${row.sourceId} path=${row.capturePath} " +
                        "state=${row.state} http=${row.lastHttpStatus} ingest=${row.serverIngestId} " +
                        "capturedAt=${row.capturedAtEpochMillis} postTime=${row.postTimeEpochMillis} " +
                        "sentAt=${row.sentAtEpochMillis} " +
                        "postToSentMs=${row.sentAtEpochMillis?.minus(row.postTimeEpochMillis)} " +
                        "roomToSentMs=${row.sentAtEpochMillis?.minus(row.capturedAtEpochMillis)}",
                )
            }
            val diagnostics = container.diagnosticInspectionFlow().first().take(12)
            diagnostics.forEach { row ->
                Log.i(TAG, "diag=${row.eventCode} details=${row.safeDetailsJson}")
            }
        }

    private companion object {
        const val TAG = "LIVE_PROOF"
        const val BNN_PACKAGE = "us.bnn.newsapp"
    }
}
