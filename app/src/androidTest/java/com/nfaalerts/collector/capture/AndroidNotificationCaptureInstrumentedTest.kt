@file:Suppress("DEPRECATION")

package com.nfaalerts.collector.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nfaalerts.collector.config.AllowlistSnapshot
import com.nfaalerts.collector.config.SourceSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AndroidNotificationCaptureInstrumentedTest {
    @Test
    fun androidActionMessageIconBitmapAndBinaryValuesAreMetadataOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, javaClass),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val remoteInput =
            android.app.RemoteInput
                .Builder("reply")
                .setLabel("Reply")
                .build()
        val action =
            Notification.Action
                .Builder(
                    Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                    "Open",
                    pendingIntent,
                ).addRemoteInput(remoteInput)
                .build()
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val extras =
            Bundle().apply {
                putCharSequence(Notification.EXTRA_TEXT, "Exact \uD83D\uDE92 text")
                putByteArray("binary", byteArrayOf(83, 69, 67, 82, 69, 84))
                putParcelable("bitmap", bitmap)
                putParcelable(
                    "icon",
                    Icon.createWithResource(context, android.R.drawable.ic_menu_view),
                )
            }
        val notification =
            Notification
                .Builder(context, "test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setExtras(extras)
                .addAction(action)
                .setStyle(
                    Notification
                        .MessagingStyle("Me")
                        .addMessage("Exact message", 123L, "Sender"),
                ).build()

        val snapshot = AndroidNotificationReader.read(notification, RawTextField.DEFAULT_ORDER)
        val result =
            SafeCanonicalSerializer().serialize(
                snapshot.envelopeValues,
                EnvelopeIdentity("event", "com.example", "key", 1, 10L),
            ) as CanonicalSerialization.Success

        assertTrue(result.json.contains("Exact message"))
        assertTrue(result.json.contains("pending_intent_omitted"))
        assertTrue(result.json.contains("binary_omitted"))
        assertTrue(result.json.contains("bitmap_metadata_only"))
        assertTrue(result.json.contains("icon_metadata_only"))
        assertFalse(result.json.contains("SECRET"))
    }

    @Test
    fun callbackGateDispatchesWithinAOneSecondBudgetWithoutWorkerExecution() {
        val dispatched = AtomicInteger()
        val source =
            SourceSelection(
                packageName = "com.example",
                appLabel = "Example",
                sourceId = "example",
                enabled = true,
                bnnMappingConfirmed = false,
                rawTextOrder = RawTextField.DEFAULT_ORDER,
            )
        val callback =
            PostedNotificationCallback(
                allowlistProvider = { AllowlistSnapshot.from(listOf(source)) },
                eventIdFactory = { "event-${dispatched.get()}" },
                clock = { 1L },
                dispatcher = CaptureDispatcher { dispatched.incrementAndGet() },
            )
        val input =
            LightweightPostedNotification(
                packageName = "com.example",
                key = "key",
                notificationId = 1,
                tag = null,
                postTimeEpochMillis = 1L,
                uid = 2,
                userId = 0,
                isOngoing = false,
                isClearable = true,
                notificationHandle = Any(),
            )

        val started = System.nanoTime()
        repeat(10_000) { callback.onNotificationPosted(input) }
        val elapsedNanos = System.nanoTime() - started
        println("M2_CALLBACK_TIMING callbacks=10000 elapsedNanos=$elapsedNanos")

        assertTrue(elapsedNanos < 1_000_000_000L)
        assertTrue(dispatched.get() == 10_000)
    }
}
