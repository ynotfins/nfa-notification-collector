package com.nfaalerts.collector

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.nfaalerts.collector.ui.AppContainerUiRepository
import com.nfaalerts.collector.ui.CollectorHomeScreen
import com.nfaalerts.collector.ui.theme.NfaCollectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MainActivity : ComponentActivity() {
    private lateinit var uiRepository: AppContainerUiRepository
    private val importConfig =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val input = contentResolver.openInputStream(uri)
                        if (input == null) {
                            uiRepository.reportImportFailure()
                            return@withContext
                        }
                        input.use {
                            val payload = it.readBounded(MAX_CONFIG_BYTES + 1)
                            try {
                                if (payload.size <= MAX_CONFIG_BYTES) {
                                    uiRepository.importConfig(payload)
                                } else {
                                    uiRepository.reportImportOversize()
                                }
                            } finally {
                                payload.fill(0)
                            }
                        }
                    } catch (_: Exception) {
                        uiRepository.reportImportFailure()
                    }
                }
            }
        }
    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        val payload = uiRepository.exportConfig()
                        try {
                            require(payload.size <= MAX_CONFIG_BYTES) { "CONFIG_PAYLOAD_LIMIT" }
                            val output = contentResolver.openOutputStream(uri)
                            if (output == null) {
                                uiRepository.reportExportFailed()
                            } else {
                                output.use { it.write(payload) }
                                uiRepository.reportExportSucceeded()
                            }
                        } finally {
                            payload.fill(0)
                        }
                    } catch (_: Exception) {
                        uiRepository.reportExportFailed()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiRepository = AppContainerUiRepository(this, (application as NfaCollectorApp).appContainer, lifecycleScope)
        setContent {
            NfaCollectorTheme {
                CollectorHomeScreen(
                    repository = uiRepository,
                    openNotificationAccessSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    openBatterySettings = {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    },
                    requestImport = { importConfig.launch(arrayOf("application/json", "text/plain")) },
                    requestExport = { exportConfig.launch("collector-config.json") },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::uiRepository.isInitialized) uiRepository.refreshPlatformState()
    }

    private companion object {
        const val MAX_CONFIG_BYTES = 1_048_576
    }
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (output.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (read < 0) break
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
