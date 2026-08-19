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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.nfaalerts.collector.ui.settings.EndpointProfileDraft
import com.nfaalerts.collector.ui.settings.SettingsDraft
import com.nfaalerts.collector.ui.sources.SourcePickerScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Composable
fun CollectorHomeScreen(
    repository: CollectorUiRepository,
    openNotificationAccessSettings: () -> Unit,
    openBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
    requestImport: () -> Unit = {},
    requestExport: () -> Unit = {},
    requestDiagnosticsExport: () -> Unit = {},
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
                    DeliveryScreen(repository, requestDiagnosticsExport, Modifier.padding(padding))
                }

                CollectorDestination.Settings -> {
                    SettingsScreen(
                        repository,
                        snapshot,
                        requestImport,
                        requestExport,
                        tokenEntryRequested,
                        { tokenEntryRequested = false },
                        openNotificationAccessSettings,
                        openBatterySettings,
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
    val pickerAccess = repository as? SourcePickerUiAccess
    val apps by produceState(emptyList(), pickerAccess) { value = pickerAccess?.sourcePickerApps() ?: emptyList() }
    if (pickerAccess == null) {
        Column(modifier.padding(16.dp)) {
            Text("Sources (${snapshot.selectedCount} / 10)", modifier = Modifier.semantics { heading() })
            Text("Sources unavailable")
        }
    } else {
        SourcePickerScreen(
            apps = apps,
            repository = pickerAccess.sourceSelectionRepository,
            sourceIdForPackage = pickerAccess::sourceIdForPackage,
            modifier = modifier,
        )
    }
}

@Composable
private fun DeliveryScreen(
    repository: CollectorUiRepository,
    requestDiagnosticsExport: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var privacyEventId by remember { mutableStateOf<String?>(null) }
    var envelope by remember { mutableStateOf<EnvelopePageState?>(null) }
    val rows by repository.deliveryRows().collectAsStateWithLifecycle(emptyList())
    val diagnostics by repository.diagnosticRows().collectAsStateWithLifecycle(emptyList())
    val feedbackFlow = remember(repository) { repository.configurationFeedback() ?: MutableStateFlow(null) }
    val transferFeedback by feedbackFlow.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Recent delivery", modifier = Modifier.semantics { heading() }) }
        item { Text("Previews are redacted. Capture records are immutable.") }
        items(rows, key = { it.eventId }) { row ->
            Column {
                Text("${row.sourceId} · ${row.packageName}")
                Text("Captured: ${row.occurredAt}")
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
        item { Text("Diagnostics", modifier = Modifier.semantics { heading() }) }
        item {
            Button(
                onClick = requestDiagnosticsExport,
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("Export safe diagnostics") }
        }
        transferFeedback?.let { message -> item { Text(message) } }
        items(diagnostics, key = { it.diagnosticId }) { row ->
            Column {
                Text("${row.eventCode} · ${row.createdAt}")
                Text(row.safeDetails)
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
                        envelope = repository.deliveryEnvelope(eventId)?.let(::EnvelopePageState)
                        privacyEventId = null
                    }
                }) { Text("I understand") }
            },
        )
    }
    envelope?.let { state ->
        AlertDialog(
            onDismissRequest = { envelope = null },
            title = { Text("Read-only local envelope") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Envelope page ${state.currentPage + 1} / ${state.pageCount}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { envelope = state.previous() },
                            enabled = state.currentPage > 0,
                        ) { Text("Previous page") }
                        TextButton(
                            onClick = { envelope = state.next() },
                            enabled = state.currentPage < state.pageCount - 1,
                        ) { Text("Next page") }
                    }
                    Text(state.currentText)
                }
            },
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
    openNotificationAccessSettings: () -> Unit,
    openBatterySettings: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var tokenEntry by remember { mutableStateOf(false) }
    var tokenWarning by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<SettingsDraft?>(null) }
    var resultText by rememberSaveable { mutableStateOf("") }
    var addProfileName by rememberSaveable { mutableStateOf("") }
    var addProfileBaseUrl by rememberSaveable { mutableStateOf("https://") }
    var addProfilePath by rememberSaveable { mutableStateOf("/v1/ingest/alerts") }
    val feedbackFlow = remember(repository) { repository.configurationFeedback() ?: MutableStateFlow(null) }
    val transferFeedback by feedbackFlow.collectAsStateWithLifecycle()
    LaunchedEffect(repository, snapshot.canonicalConfigRevision) {
        draft = repository.settingsDraft()
        editor = repository.formattedConfig()
    }
    LazyColumn(
        modifier = modifier.padding(16.dp).testTag("settings-list"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Connection settings", modifier = Modifier.semantics { heading() }) }
        item { Text("Active HTTPS endpoint: ${snapshot.endpoint}. Profiles do not fail over automatically.") }
        item {
            Button(
                onClick = {
                    tokenWarning = null
                    tokenEntry = true
                },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text("Enter token") }
        }
        tokenWarning?.let { warning -> item { Text(warning) } }
        item { Button(onClick = openNotificationAccessSettings) { Text("Open notification access settings") } }
        item { Button(onClick = openBatterySettings) { Text("Open battery settings") } }
        item { Text("Endpoint profiles") }
        draft?.profiles?.let { profiles ->
            items(profiles, key = EndpointProfileDraft::name) { profile ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Profile: ${profile.name}${if (draft?.activeProfile == profile.name) " (active)" else ""}")
                    OutlinedTextField(
                        profile.baseUrl,
                        { value -> draft = draft?.updateProfile(profile.name) { it.copy(baseUrl = value) } },
                        label = { Text("HTTPS origin for ${profile.name}") },
                    )
                    OutlinedTextField(
                        profile.ingestPath,
                        { value -> draft = draft?.updateProfile(profile.name) { it.copy(ingestPath = value) } },
                        label = { Text("Ingest path for ${profile.name}") },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { draft = draft?.copy(activeProfile = profile.name) }) {
                            Text("Select active")
                        }
                        TextButton(
                            onClick = { draft = draft?.removeProfile(profile.name) },
                            enabled = (draft?.profiles?.size ?: 0) > 1,
                        ) { Text("Remove profile") }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Add endpoint profile")
                OutlinedTextField(addProfileName, { addProfileName = it }, label = { Text("Profile name") })
                OutlinedTextField(addProfileBaseUrl, { addProfileBaseUrl = it }, label = { Text("HTTPS origin") })
                OutlinedTextField(addProfilePath, { addProfilePath = it }, label = { Text("Ingest path") })
                TextButton(onClick = {
                    val current = draft
                    if (current == null || addProfileName.isBlank()) {
                        resultText = "/endpointProfiles: PROFILE_NAME_REQUIRED — Profile name is required."
                    } else if (current.profiles.any { it.name == addProfileName }) {
                        resultText =
                            "/endpointProfiles/${addProfileName.rfc6901()}: DUPLICATE_PROFILE — Profile already exists."
                    } else {
                        draft =
                            current.copy(
                                profiles =
                                    current.profiles +
                                        EndpointProfileDraft(addProfileName, addProfileBaseUrl, addProfilePath),
                            )
                        resultText = ""
                    }
                }) { Text("Add profile") }
            }
        }
        draft?.let { current ->
            item {
                OutlinedTextField(
                    current.deviceId,
                    { draft = current.copy(deviceId = it) },
                    label = { Text("Device ID") },
                    modifier = Modifier.fillMaxWidth().testTag("device-id-form"),
                )
            }
            item {
                SettingsNumberField("Connect timeout (ms)", current.connectTimeoutMs) {
                    draft =
                        current.copy(connectTimeoutMs = it)
                }
            }
            item {
                SettingsNumberField("Read timeout (ms)", current.readTimeoutMs) {
                    draft =
                        current.copy(readTimeoutMs = it)
                }
            }
            item {
                SettingsNumberField("Initial backoff (ms)", current.initialBackoffMs) {
                    draft =
                        current.copy(initialBackoffMs = it)
                }
            }
            item {
                SettingsNumberField("Maximum backoff (ms)", current.maxBackoffMs) {
                    draft =
                        current.copy(maxBackoffMs = it)
                }
            }
            item {
                SettingsNumberField(
                    "SENT retention days",
                    current.sentDays,
                ) { draft = current.copy(sentDays = it) }
            }
            item {
                SettingsNumberField("Maximum SENT rows", current.maxSentRows) {
                    draft =
                        current.copy(maxSentRows = it)
                }
            }
            item {
                SettingsNumberField("Diagnostics retention days", current.diagnosticsDays) {
                    draft =
                        current.copy(diagnosticsDays = it)
                }
            }
            item {
                SettingsNumberField("Maximum diagnostics rows", current.diagnosticsMaxRows) {
                    draft =
                        current.copy(diagnosticsMaxRows = it)
                }
            }
            item {
                Button(onClick = {
                    scope.launch {
                        val outcome = repository.saveSettings(current)
                        resultText = outcome.safeMessage()
                        if (outcome.persisted) {
                            draft = repository.settingsDraft()
                            editor = repository.formattedConfig()
                        }
                    }
                }) { Text("Save settings forms") }
            }
        }
        item { Text("Advanced non-secret JSON") }
        item {
            OutlinedTextField(
                value = editor,
                onValueChange = { editor = it },
                label = { Text("Advanced JSON configuration") },
                modifier = Modifier.fillMaxWidth().testTag("json-config-editor"),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val errors = repository.validateConfig(editor)
                        resultText = if (errors.isEmpty()) "Configuration is valid." else errors.display()
                    }
                }) { Text("Validate only") }
                Button(onClick = {
                    scope.launch {
                        val outcome = repository.saveConfigOutcome(editor)
                        resultText = outcome.safeMessage()
                        if (outcome.persisted) {
                            draft = repository.settingsDraft()
                            editor = repository.formattedConfig()
                        }
                    }
                }) { Text("Save / Apply") }
            }
        }
        item {
            TextButton(onClick = {
                scope.launch {
                    val defaults = repository.defaultSettingsDraft()
                    val outcome = repository.saveSettings(defaults)
                    resultText = outcome.safeMessage()
                    if (outcome.persisted) {
                        draft = repository.settingsDraft()
                        editor = repository.formattedConfig()
                    }
                }
            }) { Text("Reset to approved defaults") }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = requestImport) { Text("Import configuration") }
                Button(onClick = requestExport) { Text("Export configuration") }
            }
        }
        if (resultText.isNotBlank()) item { Text(resultText) }
        transferFeedback?.let { message -> item { Text(message) } }
        item { Text("Exports never include bearer credentials, captures, diagnostics, or ciphertext.") }
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

private const val ENVELOPE_PAGE_CODE_POINTS = 8_192

private data class EnvelopePageState(
    private val value: String,
    val currentPage: Int = 0,
) {
    private val pageBoundaries: List<Int> = value.pageBoundaries()
    val pageCount: Int = (pageBoundaries.size - 1).coerceAtLeast(1)
    val currentText: String
        get() =
            value.substring(
                startIndex = pageBoundaries[currentPage],
                endIndex = pageBoundaries[currentPage + 1],
            )

    fun previous(): EnvelopePageState = copy(currentPage = (currentPage - 1).coerceAtLeast(0))

    fun next(): EnvelopePageState = copy(currentPage = (currentPage + 1).coerceAtMost(pageCount - 1))
}

private fun String.pageBoundaries(): List<Int> {
    if (isEmpty()) return listOf(0, 0)
    val boundaries = mutableListOf(0)
    var index = 0
    var codePointsInPage = 0
    while (index < length) {
        index += Character.charCount(codePointAt(index))
        codePointsInPage += 1
        if (codePointsInPage == ENVELOPE_PAGE_CODE_POINTS && index < length) {
            boundaries += index
            codePointsInPage = 0
        }
    }
    boundaries += length
    return boundaries
}

@Composable
private fun SettingsNumberField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun SettingsDraft.updateProfile(
    name: String,
    update: (EndpointProfileDraft) -> EndpointProfileDraft,
): SettingsDraft = copy(profiles = profiles.map { if (it.name == name) update(it) else it })

private fun SettingsDraft.removeProfile(name: String): SettingsDraft {
    val remaining = profiles.filterNot { it.name == name }
    return copy(
        profiles = remaining,
        activeProfile = if (activeProfile == name) remaining.first().name else activeProfile,
    )
}

private val ConfigMutationOutcome.persisted: Boolean
    get() = this is ConfigMutationOutcome.Saved || this is ConfigMutationOutcome.SavedFollowUpPending

private fun ConfigMutationOutcome.safeMessage(): String =
    when (this) {
        ConfigMutationOutcome.Saved -> "Configuration saved and applied."
        is ConfigMutationOutcome.SavedFollowUpPending -> "Configuration saved, but follow-up is pending: $code."
        is ConfigMutationOutcome.Rejected -> errors.display()
        ConfigMutationOutcome.SaveFailed -> "Configuration could not be saved."
    }

private fun List<com.nfaalerts.collector.config.ConfigValidationError>.display(): String =
    joinToString("\n") { "${it.path}: ${it.code} — ${it.safeMessage}" }

private fun String.rfc6901(): String = replace("~", "~0").replace("/", "~1")

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
