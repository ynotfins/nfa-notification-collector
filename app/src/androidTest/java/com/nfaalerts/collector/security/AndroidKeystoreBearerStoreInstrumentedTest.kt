package com.nfaalerts.collector.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreBearerStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun saveLoadReplaceAndClearUseNonExportableRandomizedAesGcm() =
        runBlocking {
            val suffix = System.nanoTime().toString()
            val alias = "nfa-test-$suffix"
            val fileName = "bearer-$suffix.json"
            val store = AndroidKeystoreBearerStore(context, alias, fileName)
            try {
                val first = "first-device-token".toCharArray()
                store.save(first)
                assertTrue(first.all { it == '\u0000' })
                val firstEnvelope = File(context.noBackupFilesDir, fileName).readBytes()
                val loadedFirst = store.load() as BearerLoadState.Present
                assertArrayEquals("first-device-token".toCharArray(), loadedFirst.value)
                loadedFirst.clear()

                val same = "first-device-token".toCharArray()
                store.save(same)
                val secondEnvelope = File(context.noBackupFilesDir, fileName).readBytes()
                assertNotEquals(firstEnvelope.decodeToString(), secondEnvelope.decodeToString())
                assertFalse(secondEnvelope.decodeToString().contains("first-device-token"))

                val replacement = "replacement-token".toCharArray()
                store.save(replacement)
                val loadedReplacement = store.load() as BearerLoadState.Present
                assertArrayEquals("replacement-token".toCharArray(), loadedReplacement.value)
                loadedReplacement.clear()

                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                assertNull(keyStore.getKey(alias, null).encoded)

                store.clear()
                assertTrue(store.load() is BearerLoadState.Missing)
            } finally {
                store.clear()
            }
        }

    @Test
    fun decryptFailureErasesUnusableCiphertextAndReportsMissing() =
        runBlocking {
            val suffix = System.nanoTime().toString()
            val alias = "nfa-corrupt-$suffix"
            val fileName = "bearer-corrupt-$suffix.json"
            val file = File(context.noBackupFilesDir, fileName)
            val store = AndroidKeystoreBearerStore(context, alias, fileName)
            try {
                store.save("temporary-token".toCharArray())
                file.writeText("""{"version":1,"iv":"AA==","ciphertext":"AA=="}""")

                assertTrue(store.load() is BearerLoadState.Missing)
                assertFalse(file.exists())
            } finally {
                store.clear()
            }
        }

    @Test
    fun concurrentFirstSaveReplaceAndLoadAreSerializedAcrossInstances() =
        runBlocking {
            val suffix = System.nanoTime().toString()
            val alias = "nfa-concurrent-$suffix"
            val fileName = "bearer-concurrent-$suffix.json"
            val stores = List(8) { AndroidKeystoreBearerStore(context, alias, fileName) }
            try {
                coroutineScope {
                    stores
                        .mapIndexed { index, store ->
                            async(Dispatchers.IO) {
                                store.save("value-$index".toCharArray())
                                store.load()
                            }
                        }.awaitAll()
                }

                val final = stores.first().load() as BearerLoadState.Present
                assertTrue(String(final.value).matches(Regex("value-[0-7]")))
                final.clear()
            } finally {
                stores.first().clear()
            }
        }

    @Test
    fun transientReadFailureDoesNotDestroyValidCiphertextOrKey() =
        runBlocking {
            val suffix = System.nanoTime().toString()
            val alias = "nfa-transient-$suffix"
            val fileName = "bearer-transient-$suffix.json"
            val normal = AndroidKeystoreBearerStore(context, alias, fileName)
            var failRead = true
            try {
                normal.save("durable-value".toCharArray())
                val before = File(context.noBackupFilesDir, fileName).readBytes()
                val flaky =
                    AndroidKeystoreBearerStore(context, alias, fileName) {
                        if (failRead) throw IOException("synthetic transient read")
                    }

                assertTrue(flaky.load() is BearerLoadState.TemporaryFailure)
                assertArrayEquals(before, File(context.noBackupFilesDir, fileName).readBytes())
                failRead = false
                val recovered = flaky.load() as BearerLoadState.Present
                assertArrayEquals("durable-value".toCharArray(), recovered.value)
                recovered.clear()
            } finally {
                normal.clear()
            }
        }

    @Test
    fun aadBoundAliasAndPurposeTamperingRetiresEnvelope() =
        runBlocking {
            val suffix = System.nanoTime().toString()
            val alias = "nfa-aad-$suffix"
            val fileName = "bearer-aad-$suffix.json"
            val file = File(context.noBackupFilesDir, fileName)
            val store = AndroidKeystoreBearerStore(context, alias, fileName)
            try {
                store.save("aad-value".toCharArray())
                file.writeText(file.readText().replace("ingest-auth", "other-purpose"))

                assertTrue(store.load() is BearerLoadState.Missing)
                assertFalse(file.exists())
            } finally {
                store.clear()
            }
        }
}
