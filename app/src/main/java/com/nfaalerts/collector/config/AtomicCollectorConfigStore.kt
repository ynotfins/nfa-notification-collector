package com.nfaalerts.collector.config

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

sealed interface ConfigSaveResult {
    data class Saved(
        val document: CollectorConfigDocument,
    ) : ConfigSaveResult

    data class Rejected(
        val errors: List<ConfigValidationError>,
    ) : ConfigSaveResult

    data object IoFailure : ConfigSaveResult
}

sealed interface ConfigLoadResult {
    data class Loaded(
        val document: CollectorConfigDocument,
    ) : ConfigLoadResult

    data object Missing : ConfigLoadResult

    data class Invalid(
        val errors: List<ConfigValidationError>,
    ) : ConfigLoadResult

    data object IoFailure : ConfigLoadResult
}

class CollectorConfigRepository(
    context: Context,
    fileName: String = FILE_NAME,
    private val afterBytesWritten: () -> Unit = {},
) {
    private val file = AtomicFile(File(context.filesDir, fileName))
    private val codec = CollectorConfigCodec()
    private val mutex = mutexes.computeIfAbsent(file.baseFile.absolutePath) { Mutex() }

    suspend fun load(): ConfigLoadResult = locked(::loadLocked)

    suspend fun savePayload(payload: ByteArray): ConfigSaveResult {
        val decoded = codec.decode(payload)
        return when (decoded) {
            is ConfigDecodeResult.Invalid -> ConfigSaveResult.Rejected(decoded.errors)
            is ConfigDecodeResult.Valid -> locked { writeLocked(decoded.document) }
        }
    }

    suspend fun importPayload(payload: ByteArray): ConfigSaveResult = savePayload(payload)

    suspend fun exportPayload(): ByteArray =
        locked {
            when (val loaded = loadLocked()) {
                is ConfigLoadResult.Loaded -> codec.exportPayload(loaded.document)
                ConfigLoadResult.Missing -> codec.exportPayload(codec.defaultDocument())
                is ConfigLoadResult.Invalid -> throw IllegalStateException(loaded.errors.first().code)
                ConfigLoadResult.IoFailure -> throw IllegalStateException("CONFIG_IO_FAILURE")
            }
        }

    suspend fun updateRoot(transform: (JsonObject) -> JsonObject): ConfigSaveResult =
        locked {
            val current =
                when (val loaded = loadLocked()) {
                    is ConfigLoadResult.Loaded -> loaded.document
                    ConfigLoadResult.Missing -> codec.defaultDocument()
                    is ConfigLoadResult.Invalid -> return@locked ConfigSaveResult.Rejected(loaded.errors)
                    ConfigLoadResult.IoFailure -> return@locked ConfigSaveResult.IoFailure
                }
            val updatedRoot =
                try {
                    transform(current.root)
                } catch (_: Exception) {
                    return@locked ConfigSaveResult.IoFailure
                }
            when (val decoded = codec.normalizeRoot(updatedRoot)) {
                is ConfigDecodeResult.Invalid -> ConfigSaveResult.Rejected(decoded.errors)
                is ConfigDecodeResult.Valid -> writeLocked(decoded.document)
            }
        }

    private fun loadLocked(): ConfigLoadResult {
        if (!file.baseFile.exists()) return ConfigLoadResult.Missing
        val payload =
            try {
                file.openRead().use(::readBounded)
            } catch (_: Exception) {
                return ConfigLoadResult.IoFailure
            }
        return when (val decoded = codec.decode(payload)) {
            is ConfigDecodeResult.Valid -> ConfigLoadResult.Loaded(decoded.document)
            is ConfigDecodeResult.Invalid -> ConfigLoadResult.Invalid(decoded.errors)
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > CollectorConfigCodec.MAX_PAYLOAD_BYTES) {
                return ByteArray(CollectorConfigCodec.MAX_PAYLOAD_BYTES + 1)
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun writeLocked(document: CollectorConfigDocument): ConfigSaveResult {
        val payload =
            try {
                codec.exportPayload(document)
            } catch (_: Exception) {
                return ConfigSaveResult.Rejected(
                    listOf(ConfigValidationError("/", "CONFIG_PAYLOAD_LIMIT", "Configuration exceeds 1 MiB.")),
                )
            }
        val stream =
            try {
                file.startWrite()
            } catch (_: Exception) {
                return ConfigSaveResult.IoFailure
            }
        return try {
            stream.write(payload)
            afterBytesWritten()
            stream.fd.sync()
            file.finishWrite(stream)
            when (val reopened = loadLocked()) {
                is ConfigLoadResult.Loaded -> ConfigSaveResult.Saved(reopened.document)
                is ConfigLoadResult.Invalid -> ConfigSaveResult.Rejected(reopened.errors)
                else -> ConfigSaveResult.IoFailure
            }
        } catch (_: Throwable) {
            file.failWrite(stream)
            ConfigSaveResult.IoFailure
        } finally {
            payload.fill(0)
        }
    }

    private suspend fun <T> locked(block: () -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock { block() }
        }

    companion object {
        const val FILE_NAME = "collector-config.json"
        private val mutexes = ConcurrentHashMap<String, Mutex>()
    }
}

typealias AtomicCollectorConfigStore = CollectorConfigRepository
