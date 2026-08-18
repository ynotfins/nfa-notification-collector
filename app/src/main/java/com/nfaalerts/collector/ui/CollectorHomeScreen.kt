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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    var destinationName by rememberSaveable { mutableStateOf(CollectorDestination.Status.name) }
    var tokenEntryRequested by remember { mutableStateOf(false) }
    val destination = CollectorDestination.valueOf(destinationName)
    val snapshot by repository.state.collectAsStateWithLifecycle()
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
        if (snapshot.loading) {
            Text("Loading collector status", modifier = Modifier.padding(padding).padding(24.dp))
        } else {
            when (destination) {
                CollectorDestination.Status -> {
                    StatusScreen(
                        snapshot = snapshot,
                        onGuidedAction = { step ->
                            when (step) {
                                GuidedSetupStep.Access -> {
                                    openNotificationAccessSettings()
                                }

                                GuidedSetupStep.Endpoint -> {
                                    destinationName = CollectorDestination.Settings.name
                                }

                                GuidedSetupStep.Token -> {
                                    destinationName = CollectorDestination.Settings.name
                                    tokenEntryRequested = true
                                }

                                GuidedSetupStep.Sources -> {
                                    destinationName = CollectorDestination.Sources.name
                                }

                                GuidedSetupStep.Verify -> {
                                    Unit
                                }

                                GuidedSetupStep.Ready -> {
                                    Unit
                                }
                            }
                        },
                        verify = { repository.verify() },
                        openBatterySettings = openBatterySettings,
                        Modifier.padding(padding),
                    )
                }

                CollectorDestination.Sources -> {
                    SourcesScreen(repository, snapshot, Modifier.padding(padding))
                }

                CollectorDestination.Delivery -> {
                    DeliveryScreen(repository, Modifier.padding(padding))
                }

                CollectorDestination.Settings -> {
                    SettingsScreen(
                        repository,
                        snapshot,
                        requestImport,
                        requestExport,
                        tokenEntryRequested,
                        { tokenEntryRequested = false },
                        Modifier.padding(padding),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(
    snapshot: CollectorUiSnapshot,
    onGuidedAction: (GuidedSetupStep) -> Unit,
    verify: suspend () -> Boolean,
    openBatterySettings: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Status", modifier = Modifier.semantics { heading() })
        if (snapshot.guidedStep == GuidedSetupStep.Ready) {
            Surface(
                color = Color(0xFFD8F3DC),
                contentColor = Color(0xFF176B2C),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag("collector-ready-container")
                        .semantics { stateDescription = "Collector ready" },
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("✓", modifier = Modifier.semantics { contentDescription = "Ready status icon" })
                    Text("Ready — required setup and local verification are complete")
                }
            }
        } else {
            Text("Setup required")
            Text("Guided setup: ${snapshot.guidedStep}")
            Button(
                onClick = {
                    if (snapshot.guidedStep == GuidedSetupStep.Verify) {
                        scope.launch { verify() }
                    } else {
                        onGuidedAction(snapshot.guidedStep)
                    }
                },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) {
                Text(snapshot.guidedStep.actionLabel())
            }
            Text(snapshot.verificationMessage)
        }
        val access = snapshot.notificationAccessState.presentation()
        Text("Notification access: ${access.label}")
        if (snapshot.notificationAccessState == NotificationAccessState.Unknown) {
            Text(access.safeExplanation)
        }
        Text("Battery reliability: ${snapshot.batteryState}")
        Button(onClick = openBatterySettings, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
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
        item { Text("Captured total: ${snapshot.totalCount}") }
        snapshot.queueCountsByState.forEach { (state, count) ->
            item { Text("${state.name}: $count") }
        }
        item { Text("Listener: ${snapshot.listenerState}") }
        item { Text("Last capture: ${snapshot.lastCapture}") }
        item { Text("Last send: ${snapshot.lastSend}") }
        item { Text("Server received: ${snapshot.serverReceivedAt}") }
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
    showTokenEntry: Boolean,
    onTokenEntryHandled: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var tokenEntry by remember { mutableStateOf(false) }
    var tokenWarning by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf("") }
    var errors by rememberSaveable { mutableStateOf("") }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Connection settings", modifier = Modifier.semantics { heading() })
        Text("Active HTTPS endpoint: ${snapshot.endpoint}. Profiles do not fail over automatically.")
        Text("Device ID: ${snapshot.deviceId}")
        Button(
            onClick = {
                tokenWarning = null
                tokenEntry = true
            },
            modifier = Modifier.sizeIn(minHeight = 48.dp),
        ) { Text("Enter token") }
        tokenWarning?.let { Text(it) }
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
    if (showTokenEntry || tokenEntry) {
        TokenEntryDialog(
            repository = repository,
            dismiss = {
                tokenEntry = false
                onTokenEntryHandled()
            },
            onSaved = { warning ->
                tokenWarning = warning
                tokenEntry = false
                onTokenEntryHandled()
            },
        )
    }
}

private fun GuidedSetupStep.actionLabel(): String =
    when (this) {
        GuidedSetupStep.Access -> "Grant notification access"
        GuidedSetupStep.Endpoint -> "Configure endpoint and device"
        GuidedSetupStep.Token -> "Enter token"
        GuidedSetupStep.Sources -> "Choose sources"
        GuidedSetupStep.Verify -> "Run local verification"
        GuidedSetupStep.Ready -> "Ready"
    }

@Composable
private fun TokenEntryDialog(
    repository: CollectorUiRepository,
    dismiss: () -> Unit,
    onSaved: (String?) -> Unit,
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
                        val outcome =
                            try {
                                repository.saveToken(transient)
                            } finally {
                                transient.fill('\u0000')
                            }
                        if (outcome.saved) {
                            token = ""
                            onSaved(outcome.safeWarning)
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
