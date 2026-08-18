package com.nfaalerts.collector.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nfaalerts.collector.capture.RawTextField
import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.ui.sources.SourcePickerScreen
import kotlinx.coroutines.launch

@Composable
fun CollectorHomeScreen(
    repository: CollectorUiRepository,
    openNotificationAccessSettings: () -> Unit,
    openBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
    requestImport: () -> Unit = {},
    requestExport: () -> Unit = {},
) {
    var batteryReviewed by rememberSaveable { mutableStateOf(false) }
    var destinationName by rememberSaveable { mutableStateOf(CollectorDestination.Status.name) }
    val destination = CollectorDestination.valueOf(destinationName)
    val snapshot by produceState<CollectorUiSnapshot?>(null, repository, destination) {
        value = repository.snapshot()
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                CollectorDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destinationName = item.name },
                        icon = {},
                        label = { Text(item.name) },
                    )
                }
            }
        },
    ) { padding ->
        val current = snapshot
        if (current == null) {
            Text("Loading collector status", modifier = Modifier.padding(padding).padding(24.dp))
        } else {
            when (destination) {
                CollectorDestination.Status -> {
                    StatusScreen(
                        current,
                        openNotificationAccessSettings,
                        openBatterySettings,
                        batteryReviewed,
                        { batteryReviewed = true },
                        Modifier.padding(padding),
                    )
                }

                CollectorDestination.Sources -> {
                    SourcesScreen(repository, current, Modifier.padding(padding))
                }

                CollectorDestination.Delivery -> {
                    DeliveryScreen(repository, Modifier.padding(padding))
                }

                CollectorDestination.Settings -> {
                    SettingsScreen(repository, current, requestImport, requestExport, Modifier.padding(padding))
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(
    snapshot: CollectorUiSnapshot,
    openNotificationAccessSettings: () -> Unit,
    openBatterySettings: () -> Unit,
    batteryReviewed: Boolean,
    onBatterySettings: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Status", modifier = Modifier.semantics { heading() })
        Text(if (snapshot.readiness.state == CollectorReadinessState.Ready) "Ready" else "Setup required")
        Text("Guided setup: ${GuidedSetup.next(snapshot.readiness, batteryReviewed)}")
        Text("Notification access: ${if (snapshot.readiness.notificationAccessGranted) "Granted" else "Required"}")
        Button(onClick = openNotificationAccessSettings, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
            Text("Open notification access settings")
        }
        Text("Battery reliability: ${snapshot.batteryState}")
        Button(onClick = {
            onBatterySettings()
            openBatterySettings()
        }, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
            Text("Open battery settings")
        }
        StatusFacts(snapshot)
    }
}

@Composable
private fun StatusFacts(snapshot: CollectorUiSnapshot) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Endpoint: ${snapshot.endpoint}") }
        item { Text("Selected sources: ${snapshot.selectedCount}") }
        item { Text("Queue: ${snapshot.queueCount}") }
        item { Text("Listener: ${snapshot.listenerState}") }
        item { Text("Last capture: ${snapshot.lastCapture}") }
        item { Text("Last send: ${snapshot.lastSend}") }
        item { Text("Last error: ${snapshot.lastError}") }
        item { Text("Network: ${snapshot.networkState}") }
    }
}

@Composable
private fun SourcesScreen(
    repository: CollectorUiRepository,
    snapshot: CollectorUiSnapshot,
    modifier: Modifier,
) {
    val sources by produceState(emptyList(), repository) { value = repository.selectedSources() }
    val pickerAccess = repository as? SourcePickerUiAccess
    val apps by produceState(emptyList(), pickerAccess) { value = pickerAccess?.sourcePickerApps() ?: emptyList() }
    var editing by remember { mutableStateOf<SourceSelection?>(null) }
    Column(modifier.padding(16.dp)) {
        Text("Sources (${snapshot.selectedCount} / 10)", modifier = Modifier.semantics { heading() })
        Text("Non-BNN sources are captured locally as BLOCKED_CONTRACT until the gateway contract is approved.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sources, key = { it.packageName }) { source ->
                Text(
                    "${source.appLabel} (${source.packageName}) — ${if (source.enabled) "Enabled" else "Disabled"}; ${source.sourceId}",
                )
                Text("Raw priority: ${source.rawTextOrder.joinToString()}")
                TextButton(onClick = { editing = source }) { Text("Edit source") }
            }
        }
        if (pickerAccess != null) {
            SourcePickerScreen(
                apps = apps,
                repository = pickerAccess.sourceSelectionRepository,
                sourceIdForPackage = pickerAccess::sourceIdForPackage,
            )
        }
    }
    editing?.let { source -> SourceEditorDialog(source, repository as? SourcePickerUiAccess) { editing = null } }
}

@Composable
private fun SourceEditorDialog(
    source: SourceSelection,
    access: SourcePickerUiAccess?,
    dismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(source.enabled) }
    var sourceId by remember { mutableStateOf(source.sourceId) }
    var bnnConfirmed by remember { mutableStateOf(source.bnnMappingConfirmed) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Edit ${source.appLabel}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text("Enabled")
                    Switch(enabled, { enabled = it })
                }
                OutlinedTextField(sourceId, { sourceId = it }, label = { Text("Source ID") })
                if (sourceId == "bnn") {
                    Text("Confirm this exact app is BNN before it may use the BNN contract.")
                    Switch(bnnConfirmed, { bnnConfirmed = it })
                }
                Text("Raw priority: ${RawTextField.DEFAULT_ORDER.joinToString()}")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    access?.sourceSelectionRepository?.upsert(
                        source.copy(enabled = enabled, sourceId = sourceId, bnnMappingConfirmed = bnnConfirmed),
                    )
                    dismiss()
                }
            }) { Text("Save source") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeliveryScreen(
    repository: CollectorUiRepository,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var privacyEventId by remember { mutableStateOf<String?>(null) }
    var envelope by remember { mutableStateOf<String?>(null) }
    val rows by produceState(emptyList(), repository) { value = repository.deliveryRows() }
    Column(modifier.padding(16.dp)) {
        Text("Recent delivery", modifier = Modifier.semantics { heading() })
        Text("Previews are redacted. Capture records are immutable.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.eventId }) { row ->
                Column {
                    Text("${row.state} · attempts ${row.attempts} · HTTP ${row.httpStatus ?: "Unknown"}")
                    Text("Failure: ${row.safeFailure ?: "None"}; server: ${row.serverId ?: "None"}")
                    Text(row.redactedPreview)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { privacyEventId = row.eventId },
                            modifier = Modifier.sizeIn(minHeight = 48.dp),
                        ) {
                            Text("View local envelope")
                        }
                        if (row.state == "RETRY_WAIT") {
                            TextButton(
                                onClick = { scope.launch { repository.retry(row.eventId) } },
                                modifier = Modifier.sizeIn(minHeight = 48.dp),
                            ) { Text("Retry eligible delivery") }
                        }
                    }
                }
            }
        }
    }
    if (privacyEventId != null) {
        AlertDialog(
            onDismissRequest = { privacyEventId = null },
            title = { Text("Private local envelope") },
            text = {
                Text(
                    "Full notification content can be private. This view is read-only and cannot be copied to the clipboard.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val eventId = privacyEventId ?: return@TextButton
                    scope.launch {
                        envelope = repository.deliveryEnvelope(eventId)
                        privacyEventId = null
                    }
                }) { Text("I understand") }
            },
        )
    }
    envelope?.let { value ->
        AlertDialog(
            onDismissRequest = { envelope = null },
            title = { Text("Read-only local envelope") },
            text = { Text(value) },
            confirmButton = { TextButton(onClick = { envelope = null }) { Text("Close") } },
        )
    }
}

@Composable
private fun SettingsScreen(
    repository: CollectorUiRepository,
    snapshot: CollectorUiSnapshot,
    requestImport: () -> Unit,
    requestExport: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var tokenEntry by rememberSaveable { mutableStateOf(false) }
    var editor by remember { mutableStateOf("") }
    var errors by rememberSaveable { mutableStateOf("") }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connection settings", modifier = Modifier.semantics { heading() })
        Text("Active HTTPS endpoint: ${snapshot.endpoint}. Profiles do not fail over automatically.")
        Text("Device ID: ${snapshot.deviceId}")
        Button(onClick = { tokenEntry = true }, modifier = Modifier.sizeIn(minHeight = 48.dp)) { Text("Enter token") }
        Text("Retry and retention are stored in the non-secret configuration.")
        TextButton(onClick = { scope.launch { editor = repository.formattedConfig() } }) { Text("Load current JSON") }
        OutlinedTextField(
            value = editor,
            onValueChange = { editor = it },
            label = { Text("Advanced JSON configuration") },
            supportingText = {
                Text(
                    errors.ifBlank {
                        "Save/Apply validates typed JSON paths and preserves the last good configuration."
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                scope.launch {
                    errors = repository.saveConfig(editor).joinToString { "${it.path}: ${it.code}" }
                }
            },
            modifier = Modifier.sizeIn(minHeight = 48.dp),
        ) { Text("Save / Apply") }
        TextButton(onClick = {
            scope.launch {
                editor = repository.formattedConfig()
                errors = ""
            }
        }) { Text("Reset") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = requestImport,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("Import configuration") }
            Button(
                onClick = requestExport,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("Export configuration") }
        }
        Text("Exports never include bearer credentials, captures, diagnostics, or ciphertext.")
    }
    if (tokenEntry) TokenEntryDialog(repository) { tokenEntry = false }
}

@Composable
private fun TokenEntryDialog(
    repository: CollectorUiRepository,
    dismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var tokenError by remember { mutableStateOf("") }
    SecureWindowEffect()
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Secure token entry") },
        text = {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Bearer token") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                supportingText = { if (tokenError.isNotBlank()) Text(tokenError) },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val transient = token.toCharArray()
                    scope.launch {
                        if (repository.saveToken(transient)) {
                            token = ""
                            dismiss()
                        } else {
                            tokenError = "Token could not be saved."
                        }
                    }
                },
            ) { Text("Save token") }
        },
        dismissButton = {
            TextButton(onClick = {
                token = ""
                dismiss()
            }) { Text("Cancel") }
        },
    )
}

@Composable
private fun SecureWindowEffect() {
    val activity = LocalView.current.context as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
