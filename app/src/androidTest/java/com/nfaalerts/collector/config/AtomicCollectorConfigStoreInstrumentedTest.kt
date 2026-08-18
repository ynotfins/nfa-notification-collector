package com.nfaalerts.collector.config

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AtomicCollectorConfigStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun failedAtomicWritePreservesLastKnownGoodBytes() =
        runBlocking {
            val name = "atomic-config-${System.nanoTime()}.json"
            val file = File(context.filesDir, name)
            try {
                val goodStore = AtomicCollectorConfigStore(context, name)
                val good = CollectorConfigCodec().exportPayload(CollectorConfigCodec().defaultDocument())
                assertTrue(goodStore.savePayload(good) is ConfigSaveResult.Saved)
                val before = file.readBytes()

                val failingStore =
                    AtomicCollectorConfigStore(context, name) {
                        throw IOException("synthetic write failure")
                    }
                val changed =
                    good
                        .decodeToString()
                        .replace(
                            "nfa-primary-phone",
                            "replacement-phone",
                        ).encodeToByteArray()

                assertTrue(failingStore.savePayload(changed) is ConfigSaveResult.IoFailure)
                assertArrayEquals(before, file.readBytes())
            } finally {
                file.delete()
                File(context.filesDir, "$name.new").delete()
                File(context.filesDir, "$name.bak").delete()
            }
        }

    @Test
    fun invalidAndFutureImportsNeverReplaceCurrentFile() =
        runBlocking {
            val name = "atomic-import-${System.nanoTime()}.json"
            val file = File(context.filesDir, name)
            try {
                val store = AtomicCollectorConfigStore(context, name)
                val good = CollectorConfigCodec().exportPayload(CollectorConfigCodec().defaultDocument())
                store.savePayload(good)
                val before = file.readBytes()

                assertTrue(store.importPayload("not-json".encodeToByteArray()) is ConfigSaveResult.Rejected)
                assertArrayEquals(before, file.readBytes())
                assertTrue(
                    store.importPayload("""{"configVersion":2,"sources":[]}""".encodeToByteArray()) is
                        ConfigSaveResult.Rejected,
                )
                assertArrayEquals(before, file.readBytes())
                assertFalse(store.exportPayload().decodeToString().contains("bearer", ignoreCase = true))
            } finally {
                file.delete()
            }
        }

    @Test
    fun invalidSourceShapeIsRejectedBeforeAtomicReplacement() =
        runBlocking {
            val name = "atomic-source-${System.nanoTime()}.json"
            val file = File(context.filesDir, name)
            try {
                val store = AtomicCollectorConfigStore(context, name)
                val good = CollectorConfigCodec().exportPayload(CollectorConfigCodec().defaultDocument())
                assertTrue(store.savePayload(good) is ConfigSaveResult.Saved)
                val before = file.readBytes()

                val result = store.savePayload("""{"configVersion":1,"sources":[{}]}""".encodeToByteArray())

                assertTrue(result is ConfigSaveResult.Rejected)
                assertArrayEquals(before, file.readBytes())
            } finally {
                file.delete()
            }
        }

    @Test
    fun savePayloadDecodeValidationAndWriteRunBehindIoBoundary() =
        runBlocking {
            val name = "atomic-boundary-${System.nanoTime()}.json"
            val checks = AtomicInteger()
            val store =
                AtomicCollectorConfigStore(
                    context = context,
                    fileName = name,
                    executionChecker = {
                        assertFalse(Looper.myLooper() == Looper.getMainLooper())
                        checks.incrementAndGet()
                    },
                )
            try {
                val payload = CollectorConfigCodec().exportPayload(CollectorConfigCodec().defaultDocument())

                val result = withContext(Dispatchers.Main) { store.savePayload(payload) }

                assertTrue(result is ConfigSaveResult.Saved)
                assertTrue(checks.get() >= 2)
            } finally {
                File(context.filesDir, name).delete()
            }
        }
}
