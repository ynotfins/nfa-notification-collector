package com.nfaalerts.collector.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface BearerLoadState {
    data class Present(
        val value: CharArray,
        val revision: Long,
    ) : BearerLoadState {
        fun clear() = value.fill('\u0000')
    }

    data object Missing : BearerLoadState

    data object TemporaryFailure : BearerLoadState
}

class AndroidKeystoreBearerStore(
    context: Context,
    private val keyAlias: String = KEY_ALIAS,
    fileName: String = FILE_NAME,
    private val beforeRead: () -> Unit = {},
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))

    suspend fun save(value: CharArray) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(value.isNotEmpty()) { "Bearer must not be empty." }
                val clearBytes = encode(value)
                val aad = aad()
                try {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                    cipher.updateAAD(aad)
                    val ciphertext = cipher.doFinal(clearBytes)
                    try {
                        writeEnvelope(cipher.iv, ciphertext)
                    } finally {
                        ciphertext.fill(0)
                    }
                } finally {
                    aad.fill(0)
                    clearBytes.fill(0)
                    value.fill('\u0000')
                }
            }
        }

    suspend fun load(): BearerLoadState =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!file.baseFile.exists()) return@withLock BearerLoadState.Missing
                val envelopeBytes =
                    try {
                        beforeRead()
                        if (file.baseFile.length() > MAX_ENVELOPE_BYTES) {
                            retireLocked()
                            return@withLock BearerLoadState.Missing
                        }
                        file.openRead().use { it.readBytes() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: IOException) {
                        return@withLock BearerLoadState.TemporaryFailure
                    } catch (_: SecurityException) {
                        return@withLock BearerLoadState.TemporaryFailure
                    }
                val envelope =
                    try {
                        Json.parseToJsonElement(envelopeBytes.toString(StandardCharsets.UTF_8)).jsonObject
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        envelopeBytes.fill(0)
                        retireLocked()
                        return@withLock BearerLoadState.Missing
                    }
                var clearBytes: ByteArray? = null
                val aad = aad()
                try {
                    require(envelope.getValue("version").jsonPrimitive.int == VERSION)
                    require(envelope.getValue("keyAlias").jsonPrimitive.content == keyAlias)
                    require(envelope.getValue("purpose").jsonPrimitive.content == PURPOSE)
                    val iv = Base64.decode(envelope.getValue("iv").jsonPrimitive.content, Base64.NO_WRAP)
                    val ciphertext =
                        Base64.decode(envelope.getValue("ciphertext").jsonPrimitive.content, Base64.NO_WRAP)
                    try {
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
                        cipher.updateAAD(aad)
                        clearBytes = cipher.doFinal(ciphertext)
                        BearerLoadState.Present(decode(clearBytes), fingerprint(envelopeBytes))
                    } finally {
                        iv.fill(0)
                        ciphertext.fill(0)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    BearerLoadState.TemporaryFailure
                } catch (_: GeneralSecurityException) {
                    retireLocked()
                    BearerLoadState.Missing
                } catch (_: IllegalArgumentException) {
                    retireLocked()
                    BearerLoadState.Missing
                } catch (_: NoSuchElementException) {
                    retireLocked()
                    BearerLoadState.Missing
                } finally {
                    aad.fill(0)
                    envelopeBytes.fill(0)
                    clearBytes?.fill(0)
                }
            }
        }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            mutex.withLock { retireLocked() }
        }

    suspend fun revisionFingerprint(): Long? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!file.baseFile.exists()) return@withLock 0L
                val bytes =
                    try {
                        beforeRead()
                        file.openRead().use { it.readBytes() }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return@withLock null
                    }
                try {
                    fingerprint(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
        }

    private fun retireLocked() {
        file.delete()
        runCatching {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(keyAlias)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec
                .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun existingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return keyStore.getKey(keyAlias, null) as? SecretKey
            ?: throw GeneralSecurityException("Keystore key is missing.")
    }

    private fun writeEnvelope(
        iv: ByteArray,
        ciphertext: ByteArray,
    ) {
        val payload =
            JsonObject(
                sortedMapOf(
                    "ciphertext" to JsonPrimitive(Base64.encodeToString(ciphertext, Base64.NO_WRAP)),
                    "iv" to JsonPrimitive(Base64.encodeToString(iv, Base64.NO_WRAP)),
                    "keyAlias" to JsonPrimitive(keyAlias),
                    "purpose" to JsonPrimitive(PURPOSE),
                    "version" to JsonPrimitive(VERSION),
                ),
            ).toString().encodeToByteArray()
        val stream = file.startWrite()
        try {
            stream.write(payload)
            stream.fd.sync()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        } finally {
            payload.fill(0)
        }
    }

    private fun aad(): ByteArray = "$VERSION\u0000$keyAlias\u0000$PURPOSE".encodeToByteArray()

    private fun fingerprint(bytes: ByteArray): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return try {
            ByteBuffer.wrap(digest, 0, java.lang.Long.BYTES).long
        } finally {
            digest.fill(0)
        }
    }

    private fun encode(value: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value))
        return try {
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }

    private fun decode(value: ByteArray): CharArray {
        val buffer = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value))
        return try {
            CharArray(buffer.remaining()).also(buffer::get)
        } finally {
            if (buffer.hasArray()) buffer.array().fill('\u0000')
        }
    }

    companion object {
        private const val VERSION = 1
        private const val TAG_BITS = 128
        private const val MAX_ENVELOPE_BYTES = 65_536L
        private const val PURPOSE = "ingest-auth"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS = "nfa-ingest-bearer-v1"
        private const val FILE_NAME = "ingest-bearer-v1.json"
        private val mutex = Mutex()
    }
}
