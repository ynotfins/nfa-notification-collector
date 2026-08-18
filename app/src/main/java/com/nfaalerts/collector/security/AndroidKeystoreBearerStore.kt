package com.nfaalerts.collector.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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
    ) : BearerLoadState {
        fun clear() = value.fill('\u0000')
    }

    data object Missing : BearerLoadState
}

class AndroidKeystoreBearerStore(
    context: Context,
    private val keyAlias: String = KEY_ALIAS,
    fileName: String = FILE_NAME,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))

    suspend fun save(value: CharArray) =
        withContext(Dispatchers.IO) {
            require(value.isNotEmpty()) { "Bearer must not be empty." }
            val clearBytes = encode(value)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                val ciphertext = cipher.doFinal(clearBytes)
                try {
                    writeEnvelope(cipher.iv, ciphertext)
                } finally {
                    ciphertext.fill(0)
                }
            } finally {
                clearBytes.fill(0)
                value.fill('\u0000')
            }
        }

    suspend fun load(): BearerLoadState =
        withContext(Dispatchers.IO) {
            if (!file.baseFile.exists()) return@withContext BearerLoadState.Missing
            var clearBytes: ByteArray? = null
            try {
                val root = Json.parseToJsonElement(file.openRead().bufferedReader().use { it.readText() }).jsonObject
                require(root.getValue("version").jsonPrimitive.int == VERSION)
                val iv = Base64.decode(root.getValue("iv").jsonPrimitive.content, Base64.NO_WRAP)
                val ciphertext = Base64.decode(root.getValue("ciphertext").jsonPrimitive.content, Base64.NO_WRAP)
                try {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
                    clearBytes = cipher.doFinal(ciphertext)
                    BearerLoadState.Present(decode(clearBytes))
                } finally {
                    iv.fill(0)
                    ciphertext.fill(0)
                }
            } catch (_: Exception) {
                file.delete()
                deleteKey()
                BearerLoadState.Missing
            } finally {
                clearBytes?.fill(0)
            }
        }

    suspend fun clear() =
        withContext(Dispatchers.IO) {
            file.delete()
            deleteKey()
        }

    suspend fun revisionFingerprint(): Long =
        withContext(Dispatchers.IO) {
            if (!file.baseFile.exists()) return@withContext 0L
            val digest = MessageDigest.getInstance("SHA-256").digest(file.openRead().use { it.readBytes() })
            ByteBuffer.wrap(digest, 0, java.lang.Long.BYTES).long
        }

    private fun deleteKey() {
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
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS = "nfa-ingest-bearer-v1"
        private const val FILE_NAME = "ingest-bearer-v1.json"
    }
}
