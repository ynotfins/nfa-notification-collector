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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var uiRepository: AppContainerUiRepository
    private val importConfig =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                contentResolver.openInputStream(uri)?.use { uiRepository.importConfig(it.readBytes()) }
            }
        }
    private val exportConfig =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri ?: return@registerForActivityResult
            lifecycleScope.launch {
                contentResolver.openOutputStream(uri)?.use { it.write(uiRepository.exportConfig()) }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uiRepository = AppContainerUiRepository(this, (application as NfaCollectorApp).appContainer)
        setContent {
            NfaCollectorTheme {
                CollectorHomeScreen(
                    repository = uiRepository,
                    openNotificationAccessSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    openBatterySettings = { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) },
                    requestImport = { importConfig.launch(arrayOf("application/json", "text/plain")) },
                    requestExport = { exportConfig.launch("collector-config.json") },
                )
            }
        }
    }
}
