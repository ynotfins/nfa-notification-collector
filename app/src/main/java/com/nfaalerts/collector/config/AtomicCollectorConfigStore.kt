package com.nfaalerts.collector.config

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

class AtomicCollectorConfigStore(
    context: Context,
    fileName: String = FILE_NAME,
    private val afterBytesWritten: () -> Unit = {},
) {
    private val file = AtomicFile(File(context.filesDir, fileName))
    private val codec = CollectorConfigCodec()

    suspend fun load(): ConfigLoadResult =
        withContext(Dispatchers.IO) {
            if (!file.baseFile.exists()) return@withContext ConfigLoadResult.Missing
            val payload =
                runCatching { file.openRead().use { it.readBytes() } }.getOrNull()
                    ?: return@withContext ConfigLoadResult.IoFailure
            when (val decoded = codec.decode(payload)) {
                is ConfigDecodeResult.Valid -> ConfigLoadResult.Loaded(decoded.document)
                is ConfigDecodeResult.Invalid -> ConfigLoadResult.Invalid(decoded.errors)
            }
        }

    suspend fun savePayload(payload: ByteArray): ConfigSaveResult =
        withContext(Dispatchers.IO) {
            when (val decoded = codec.decode(payload)) {
                is ConfigDecodeResult.Invalid -> ConfigSaveResult.Rejected(decoded.errors)
                is ConfigDecodeResult.Valid -> write(decoded.document)
            }
        }

    suspend fun importPayload(payload: ByteArray): ConfigSaveResult = savePayload(payload)

    suspend fun exportPayload(): ByteArray =
        withContext(Dispatchers.IO) {
            val payload = file.openRead().use { it.readBytes() }
            when (val decoded = codec.decode(payload)) {
                is ConfigDecodeResult.Valid -> codec.exportPayload(decoded.document)
                is ConfigDecodeResult.Invalid -> throw IllegalStateException(decoded.errors.first().code)
            }
        }

    private fun write(document: CollectorConfigDocument): ConfigSaveResult {
        val stream =
            try {
                file.startWrite()
            } catch (_: Exception) {
                return ConfigSaveResult.IoFailure
            }
        return try {
            stream.write(codec.exportPayload(document))
            afterBytesWritten()
            stream.fd.sync()
            file.finishWrite(stream)
            ConfigSaveResult.Saved(document)
        } catch (_: Throwable) {
            file.failWrite(stream)
            ConfigSaveResult.IoFailure
        }
    }

    companion object {
        const val FILE_NAME = "collector-config.json"
    }
}
