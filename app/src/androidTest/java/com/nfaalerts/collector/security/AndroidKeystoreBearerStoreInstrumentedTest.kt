package com.nfaalerts.collector.security

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
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
}
