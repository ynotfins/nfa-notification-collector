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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.ui.settings.EndpointProfileDraft
import com.nfaalerts.collector.ui.settings.SettingsDraft
import com.nfaalerts.collector.ui.sources.SourcePickerScreen
import com.nfaalerts.collector.ui.theme.RgdsSemanticColors
import com.nfaalerts.collector.ui.theme.RgdsTheme
import com.nfaalerts.collector.ui.theme.RgdsThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun CollectorHomeScreen(
    repository: CollectorUiRepository,
    openNotificationAccessSettings: () -> Unit,
    openBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
    requestForegroundNotificationPermission: () -> Unit = {},
    openAppDetailsSettings: () -> Unit = {},
    requestImport: () -> Unit = {},
    requestExport: () -> Unit = {},
    requestDiagnosticsExport: () -> Unit = {},
) {
    var destinationName by rememberSaveable { mutableStateOf(CollectorDestination.Status.name) }
    var tokenEntryRequested by remember { mutableStateOf(false) }
    val destination = CollectorDestination.valueOf(destinationName)
    val snapshot by repository.state.collectAsStateWithLifecycle()
    val spacing = RgdsTheme.spacing
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                CollectorDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destinationName = item.name },
                        icon = {
                            RgdsCardBadge(item.label.take(1), size = spacing.iconXl)
                        },
                        label = { Text(item.label) },
                        modifier = Modifier.semantics { contentDescription = "Open ${item.label}" },
                    )
                }
            }
        },
    ) { padding ->
        if (snapshot.loading) {
            Text("Loading collector status", modifier = Modifier.padding(padding).padding(spacing.xl))
        } else {
            when (destination) {
                CollectorDestination.Status -> {
                    StatusScreen(
                        repository = repository,
                        snapshot = snapshot,
                        openDestination = { target -> destinationName = target.name },
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
                        openBatterySettings = openBatterySettings,
                        requestForegroundNotificationPermission = requestForegroundNotificationPermission,
                        openAppDetailsSettings = openAppDetailsSettings,
                        Modifier.padding(padding),
                    )
                }

                CollectorDestination.Sources -> {
                    SourcesScreen(repository, snapshot, Modifier.padding(padding))
                }

                CollectorDestination.Delivery -> {
                    DeliveryScreen(repository, snapshot, requestDiagnosticsExport, Modifier.padding(padding))
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
                        requestForegroundNotificationPermission,
                        openAppDetailsSettings,
                        Modifier.padding(padding),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusScreen(
    repository: CollectorUiRepository,
    snapshot: CollectorUiSnapshot,
    openDestination: (CollectorDestination) -> Unit,
    onGuidedAction: (GuidedSetupStep) -> Unit,
    openBatterySettings: () -> Unit,
    requestForegroundNotificationPermission: () -> Unit,
    openAppDetailsSettings: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val spacing = RgdsTheme.spacing
    val colors = RgdsTheme.colors
    var flushMessage by remember { mutableStateOf<String?>(null) }
    val access = snapshot.notificationAccessState.presentation()
    val setupFacts =
        "Notification access: ${access.label}. Battery: ${snapshot.batteryState}. " +
            "Background: ${snapshot.backgroundActivityState}. Reliability notification: " +
            "${snapshot.foregroundNotificationState}. Listener: ${snapshot.listenerState}. " +
            if (snapshot.notificationAccessState == NotificationAccessState.Unknown) access.safeExplanation else ""
    val setupAction =
        when {
            snapshot.guidedStep != GuidedSetupStep.Ready -> {
                snapshot.guidedStep.actionLabel() to { onGuidedAction(snapshot.guidedStep) }
            }

            snapshot.batteryOptimizationState != BatteryOptimizationState.Exempt -> {
                "Review battery setting" to openBatterySettings
            }

            snapshot.backgroundActivityState != BackgroundActivityState.Allowed -> {
                "Review background setting" to openAppDetailsSettings
            }

            snapshot.foregroundNotificationState == ForegroundNotificationState.Required -> {
                "Allow reliability notification" to requestForegroundNotificationPermission
            }

            !snapshot.listenerConnected -> {
                "Reconnect notification access" to { onGuidedAction(GuidedSetupStep.Access) }
            }

            else -> {
                null
            }
        }
    LazyColumn(
        modifier = modifier.padding(spacing.md).testTag("status-list"),
        verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
    ) {
        item { Text("Home", modifier = Modifier.semantics { heading() }) }
        item {
            HomeTile(
                title = "Setup health",
                summary =
                    if (snapshot.operationallyHealthy) {
                        "All required collector checks are green"
                    } else {
                        "Action needed"
                    },
                tip =
                    setupFacts +
                        " Checks endpoint, token, device ID, and sources. Recents Keep open is optional.",
                containerColor =
                    if (snapshot.operationallyHealthy) {
                        colors.successContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                contentColor =
                    if (snapshot.operationallyHealthy) {
                        colors.onSuccessContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                actionLabel = setupAction?.first,
                onAction = setupAction?.second,
                modifier =
                    Modifier
                        .testTag("collector-ready-container")
                        .semantics {
                            stateDescription =
                                if (snapshot.operationallyHealthy) "Collector ready" else "Setup required"
                        },
            )
        }
        item {
            HomeTile(
                title = "Sources",
                summary = "${snapshot.selectedCount} of 10 selected",
                tip =
                    "BNN is first-class. Other apps are captured locally as BLOCKED_CONTRACT until the PC " +
                        "contract approves their source IDs.",
                containerColor = colors.infoContainer,
                contentColor = colors.onInfoContainer,
                actionLabel = "Manage sources",
                onAction = { openDestination(CollectorDestination.Sources) },
            )
        }
        item {
            HomeTile(
                title = "Queue",
                summary = "${snapshot.queueCount} not sent · ${snapshot.totalCount} captured",
                tip =
                    "Room is the authority. Pending and retry rows are never removed by sent-history " +
                        "retention and replay in capture order.",
                containerColor =
                    if (snapshot.queueCount > 0) colors.warningContainer else colors.successContainer,
                contentColor =
                    if (snapshot.queueCount > 0) colors.onWarningContainer else colors.onSuccessContainer,
                actionLabel = "Open outbox",
                onAction = { openDestination(CollectorDestination.Delivery) },
            )
        }
        item {
            HomeTile(
                title = "Send now",
                summary = flushMessage ?: "Run the ordered drain now",
                tip =
                    "Useful after connectivity returns. This never bypasses authentication, retry safety, " +
                        "or queue order.",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                actionLabel = "Flush queue now",
                onAction = {
                    scope.launch {
                        val attempted = repository.flushNow()
                        flushMessage = "$attempted queue item(s) attempted in capture order"
                    }
                },
            )
        }
        item {
            HomeTile(
                title = "Settings",
                summary = snapshot.endpoint,
                tip =
                    "One active endpoint, one Keystore-backed bearer, safe presets, and advanced controls " +
                        "when needed.",
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionLabel = "Open settings",
                onAction = { openDestination(CollectorDestination.Settings) },
            )
        }
        item {
            HomeTile(
                title = "Tips",
                summary = "Durable after Room write",
                tip =
                    "Android can miss notifications while force-stopped or while Notification Access is off. " +
                        "Once Room persists a capture, ordered replay protects it for well beyond 48 hours. " +
                        "Samsung Recents Keep open is optional.",
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actionLabel = "Open app details",
                onAction = openAppDetailsSettings,
            )
        }
        item { Text("Live details", modifier = Modifier.semantics { heading() }) }
        statusFacts(snapshot)
    }
}

@Composable
private fun HomeTile(
    title: String,
    summary: String,
    tip: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    RgdsCard(
        title = title,
        subtitle = summary,
        badgeLabel = title.take(2),
        containerColor = containerColor,
        contentColor = contentColor,
        modifier = modifier,
    ) {
        Text("Tip: $tip", style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = RgdsTheme.spacing.buttonXl),
            ) {
                Text(actionLabel)
            }
        }
    }
}

private fun LazyListScope.statusFacts(snapshot: CollectorUiSnapshot) {
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
    item { Text("Last drain: ${snapshot.lastDrain}") }
    item { Text("Server received: ${snapshot.serverReceivedAt}") }
    item { Text("Last error: ${snapshot.lastError}") }
    item { Text("Network: ${snapshot.networkState}") }
}

private data class InstalledAppsLoad(
    val loading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
)

@Composable
private fun SourcesScreen(
    repository: CollectorUiRepository,
    snapshot: CollectorUiSnapshot,
    modifier: Modifier,
) {
    val pickerAccess = repository as? SourcePickerUiAccess
    var refreshKey by remember { mutableStateOf(0) }
    val appLoad by produceState(InstalledAppsLoad(), pickerAccess, refreshKey) {
        value = InstalledAppsLoad(loading = false, apps = pickerAccess?.sourcePickerApps() ?: emptyList())
    }
    val spacing = RgdsTheme.spacing
    if (pickerAccess == null) {
        Column(modifier.padding(spacing.md)) {
            Text("Sources (${snapshot.selectedCount} / 10)", modifier = Modifier.semantics { heading() })
            Text("Sources unavailable")
        }
    } else {
        SourcePickerScreen(
            apps = appLoad.apps,
            repository = pickerAccess.sourceSelectionRepository,
            appsLoading = appLoad.loading,
            sourceIdForPackage = pickerAccess::sourceIdForPackage,
            refreshApps = { refreshKey += 1 },
            modifier = modifier,
        )
    }
}

@Composable
private fun DeliveryScreen(
    repository: CollectorUiRepository,
    snapshot: CollectorUiSnapshot,
    requestDiagnosticsExport: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val spacing = RgdsTheme.spacing
    val colors = RgdsTheme.colors
    var privacyEventId by remember { mutableStateOf<String?>(null) }
    var envelope by remember { mutableStateOf<EnvelopePageState?>(null) }
    var flushMessage by remember { mutableStateOf<String?>(null) }
    val rows by repository.deliveryRows().collectAsStateWithLifecycle(emptyList())
    val diagnostics by repository.diagnosticRows().collectAsStateWithLifecycle(emptyList())
    val feedbackFlow = remember(repository) { repository.configurationFeedback() ?: MutableStateFlow(null) }
    val transferFeedback by feedbackFlow.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = modifier.padding(spacing.md).testTag("delivery-list"),
        verticalArrangement = Arrangement.spacedBy(spacing.metadataGap),
    ) {
        item {
            Text(
                "Queue & delivery",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            Text(
                "Every selected notification is written to Room first. Cards remain immutable and replay in capture order.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item {
            RgdsCard(
                title = "Outbox health",
                subtitle = "${snapshot.queueCount} waiting · ${snapshot.totalCount} captured",
                badgeLabel = "Q",
                containerColor = if (snapshot.queueCount > 0) colors.warningContainer else colors.successContainer,
                contentColor =
                    if (snapshot.queueCount > 0) colors.onWarningContainer else colors.onSuccessContainer,
            ) {
                Text("Last ordered drain: ${snapshot.lastDrain}")
                Text("Tip: Send now is safe after reconnect and never skips an older retrying row.")
                Button(
                    onClick = {
                        scope.launch {
                            val attempted = repository.flushNow()
                            flushMessage = "Flush completed: $attempted queue item(s) attempted in capture order."
                        }
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = RgdsTheme.spacing.buttonXl)
                            .testTag("flush-now"),
                ) {
                    Text("Send queued alerts now")
                }
                flushMessage?.let { Text(it) }
            }
        }
        if (rows.isEmpty()) {
            item {
                RgdsEmptyState(
                    title = "No captured notifications yet",
                    message = "Choose BNN or another source, then wait for its next notification.",
                    badgeLabel = "0",
                ) {
                    Text("Tip: Preview and delivery actions appear only after Room has persisted a real capture.")
                }
            }
        } else {
            item {
                Button(
                    onClick = { privacyEventId = rows.first().eventId },
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = RgdsTheme.spacing.buttonXl),
                ) {
                    Text("Preview latest capture")
                }
            }
        }
        items(rows, key = { it.eventId }) { row ->
            val presentation = deliveryPresentation(row.state, colors)
            RgdsCard(
                title = "${row.sourceId.uppercase()} delivery",
                subtitle = row.packageName,
                badgeLabel = presentation.badge,
                containerColor = presentation.container,
                contentColor = presentation.content,
                modifier = Modifier.testTag("delivery-card-${row.eventId}"),
            ) {
                Text("State: ${presentation.label}", style = MaterialTheme.typography.titleMedium)
                Text("Captured: ${row.occurredAt}")
                Text("Attempts: ${row.attempts} · HTTP: ${row.httpStatus ?: "Not received"}")
                if (row.safeFailure != null) Text("Needs attention: ${row.safeFailure}")
                if (row.serverId != null) Text("Server receipt: ${row.serverId}")
                if (row.state == "RETRY_WAIT") {
                    Button(
                        onClick = { scope.launch { repository.retry(row.eventId) } },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = RgdsTheme.spacing.buttonXl)
                                .semantics { contentDescription = "Retry delivery ${row.eventId}" },
                    ) { Text("Retry this delivery") }
                    TextButton(
                        onClick = { privacyEventId = row.eventId },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = RgdsTheme.spacing.touchTarget)
                                .semantics { contentDescription = "View local envelope for ${row.eventId}" },
                    ) { Text("View private local envelope") }
                } else {
                    Button(
                        onClick = { privacyEventId = row.eventId },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .sizeIn(minHeight = RgdsTheme.spacing.buttonXl)
                                .semantics { contentDescription = "View local envelope for ${row.eventId}" },
                    ) { Text("View delivery details") }
                }
            }
        }
        item { Text("Safe diagnostics", modifier = Modifier.semantics { heading() }) }
        item {
            RgdsCard(
                title = "Diagnostics export",
                subtitle = "Secret-free health and retry evidence",
                badgeLabel = "LOG",
            ) {
                Text("Tip: Diagnostics omit bearer values and private notification content.")
                Button(
                    onClick = requestDiagnosticsExport,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = RgdsTheme.spacing.buttonXl),
                ) { Text("Export safe diagnostics") }
                transferFeedback?.let { Text(it) }
            }
        }
        items(diagnostics, key = { it.diagnosticId }) { row ->
            RgdsCard(
                title = row.eventCode,
                subtitle = row.createdAt.toString(),
                badgeLabel = "LOG",
            ) {
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
                LazyColumn(
                    modifier = Modifier.testTag("envelope-dialog-list"),
                    verticalArrangement = Arrangement.spacedBy(RgdsTheme.spacing.metadataGap),
                ) {
                    item { Text("Envelope page ${state.currentPage + 1} / ${state.pageCount}") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(RgdsTheme.spacing.metadataGap)) {
                            TextButton(
                                onClick = { envelope = state.previous() },
                                enabled = state.currentPage > 0,
                            ) { Text("Previous page") }
                            TextButton(
                                onClick = { envelope = state.next() },
                                enabled = state.currentPage < state.pageCount - 1,
                            ) { Text("Next page") }
                        }
                    }
                    item { Text(state.currentText) }
                }
            },
            confirmButton = { TextButton(onClick = { envelope = null }) { Text("Close") } },
        )
    }
}

private data class DeliveryPresentation(
    val label: String,
    val badge: String,
    val container: androidx.compose.ui.graphics.Color,
    val content: androidx.compose.ui.graphics.Color,
)

@Composable
private fun deliveryPresentation(
    state: String,
    colors: RgdsSemanticColors,
): DeliveryPresentation =
    when (state) {
        "SENT" -> {
            DeliveryPresentation("Sent", "OK", colors.successContainer, colors.onSuccessContainer)
        }

        "PENDING", "SENDING" -> {
            DeliveryPresentation("Waiting to send", "Q", colors.infoContainer, colors.onInfoContainer)
        }

        "RETRY_WAIT", "PAUSED_AUTH" -> {
            DeliveryPresentation("Needs retry", "!", colors.warningContainer, colors.onWarningContainer)
        }

        "BLOCKED_CONTRACT" -> {
            DeliveryPresentation(
                "Saved locally — source not approved for live ingest",
                "HOLD",
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }

        else -> {
            DeliveryPresentation(
                "Needs attention",
                "!",
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
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
    requestForegroundNotificationPermission: () -> Unit,
    openAppDetailsSettings: () -> Unit,
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
    var showAdvancedEndpoints by rememberSaveable { mutableStateOf(false) }
    var showCustomDeviceId by rememberSaveable { mutableStateOf(false) }
    var showAdvancedJson by rememberSaveable { mutableStateOf(false) }
    val feedbackFlow = remember(repository) { repository.configurationFeedback() ?: MutableStateFlow(null) }
    val transferFeedback by feedbackFlow.collectAsStateWithLifecycle()
    val reliabilityAction =
        when {
            snapshot.notificationAccessState != NotificationAccessState.Granted -> {
                "Fix notification access" to openNotificationAccessSettings
            }

            snapshot.batteryOptimizationState != BatteryOptimizationState.Exempt -> {
                "Fix battery setting" to openBatterySettings
            }

            snapshot.backgroundActivityState != BackgroundActivityState.Allowed -> {
                "Fix background setting" to openAppDetailsSettings
            }

            snapshot.foregroundNotificationState == ForegroundNotificationState.Required -> {
                "Allow reliability notification" to requestForegroundNotificationPermission
            }

            else -> {
                "Review reliability settings" to openBatterySettings
            }
        }
    LaunchedEffect(repository, snapshot.canonicalConfigRevision) {
        draft = repository.settingsDraft()
        editor = repository.formattedConfig()
    }
    LazyColumn(
        modifier = modifier.padding(RgdsTheme.spacing.md).testTag("settings-list"),
        verticalArrangement = Arrangement.spacedBy(RgdsTheme.spacing.sm),
    ) {
        item { Text("Connection settings", modifier = Modifier.semantics { heading() }) }
        draft?.let { current ->
            item {
                SettingsDropdown(
                    label = "Theme",
                    currentValue = current.theme,
                    options = RgdsThemeMode.entries.map { SettingOption(it.displayName, it.dataTheme) },
                    tip = "Changes all RGDS colors immediately. Exactly four approved themes are available.",
                    onSelected = { selected ->
                        scope.launch {
                            val outcome = repository.saveSettings(current.copy(theme = selected))
                            resultText = outcome.safeMessage()
                            if (outcome.persisted) draft = repository.settingsDraft()
                        }
                    },
                )
            }
            item {
                SettingsDropdown(
                    label = "Endpoint profile",
                    currentValue = current.activeProfile,
                    options =
                        current.profiles.map { profile ->
                            SettingOption(profile.displayName(), profile.name)
                        },
                    tip =
                        "Tailscale (production) is the default. Android loopback is the phone itself, and " +
                            "cleartext HTTP is disabled. Use the custom editor only for an approved HTTPS gateway.",
                    onSelected = { selected ->
                        scope.launch {
                            val outcome = repository.saveSettings(current.copy(activeProfile = selected))
                            resultText = outcome.safeMessage()
                            if (outcome.persisted) draft = repository.settingsDraft()
                        }
                    },
                )
            }
        }
        item {
            RgdsCard(
                title = "Ingest authentication",
                subtitle = snapshot.tokenStatusLabel(),
                badgeLabel = "KEY",
                containerColor =
                    if (snapshot.readiness.bearerSaved && snapshot.bearerRevisionOk) {
                        RgdsTheme.colors.successContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                contentColor =
                    if (snapshot.readiness.bearerSaved && snapshot.bearerRevisionOk) {
                        RgdsTheme.colors.onSuccessContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
            ) {
                Text("Tip: The saved token is never redisplayed. Replace opens a fresh secure editor.")
                Button(
                    onClick = {
                        tokenWarning = null
                        tokenEntry = true
                    },
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = RgdsTheme.spacing.buttonXl),
                ) { Text(if (snapshot.readiness.bearerSaved) "Replace token" else "Enter token") }
                tokenWarning?.let { Text(it) }
            }
        }
        item {
            RgdsCard(
                title = "Reliability & permissions",
                subtitle =
                    "Access ${snapshot.notificationAccessState} · Battery ${snapshot.batteryState} · " +
                        "Listener ${snapshot.listenerState}",
                badgeLabel = if (snapshot.operationallyHealthy) "OK" else "!",
                containerColor =
                    if (snapshot.operationallyHealthy) {
                        RgdsTheme.colors.successContainer
                    } else {
                        RgdsTheme.colors.warningContainer
                    },
                contentColor =
                    if (snapshot.operationallyHealthy) {
                        RgdsTheme.colors.onSuccessContainer
                    } else {
                        RgdsTheme.colors.onWarningContainer
                    },
            ) {
                Text("Tip: Return from Android Settings and this card re-checks the real OS state automatically.")
                Button(
                    onClick = reliabilityAction.second,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = RgdsTheme.spacing.buttonXl),
                ) { Text(reliabilityAction.first) }
                TextButton(
                    onClick = openNotificationAccessSettings,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = RgdsTheme.spacing.touchTarget),
                ) { Text("Notification Access details") }
                TextButton(
                    onClick = openBatterySettings,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = RgdsTheme.spacing.touchTarget),
                ) { Text("Battery settings details") }
                TextButton(
                    onClick = openAppDetailsSettings,
                    modifier = Modifier.fillMaxWidth().sizeIn(minHeight = RgdsTheme.spacing.touchTarget),
                ) { Text("App background details") }
            }
        }
        item {
            TextButton(onClick = { showAdvancedEndpoints = !showAdvancedEndpoints }) {
                Text(if (showAdvancedEndpoints) "Hide custom endpoint editor" else "Custom / edit endpoint…")
            }
        }
        if (showAdvancedEndpoints) {
            item {
                Text(
                    "Tip: Custom endpoints must be approved HTTPS origins. Saving an invalid profile is blocked.",
                )
            }
            draft?.profiles?.let { profiles ->
                items(profiles, key = EndpointProfileDraft::name) { profile ->
                    RgdsCard(
                        title = profile.displayName(),
                        subtitle = profile.baseUrl,
                        badgeLabel = "URL",
                    ) {
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
                        TextButton(
                            onClick = { draft = draft?.removeProfile(profile.name) },
                            enabled = (draft?.profiles?.size ?: 0) > 1,
                            modifier =
                                Modifier.semantics {
                                    contentDescription = "Remove profile ${profile.name}"
                                },
                        ) { Text("Remove profile") }
                    }
                }
            }
            item {
                RgdsCard(
                    title = "Add custom HTTPS profile",
                    subtitle = "Advanced gateway configuration",
                    badgeLabel = "NEW",
                ) {
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
                            resultText = "Custom profile added to the draft. Save settings forms to apply it."
                        }
                    }) { Text("Add custom profile") }
                }
            }
        }
        draft?.let { current ->
            item {
                RgdsCard(
                    title = "Samsung Recents",
                    subtitle = "Optional convenience — never a readiness gate",
                    badgeLabel = "OPT",
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Keep open selected", style = MaterialTheme.typography.titleMedium)
                        Switch(
                            checked = current.recentsLocked,
                            onCheckedChange = { draft = current.copy(recentsLocked = it) },
                            modifier =
                                Modifier.semantics {
                                    stateDescription =
                                        if (current.recentsLocked) "Optional tip enabled" else "Optional tip disabled"
                                },
                        )
                    }
                }
            }
            item {
                val deviceOptions =
                    buildList {
                        add(SettingOption("Primary phone", "nfa-primary-phone"))
                        if (current.deviceId != "nfa-primary-phone") {
                            add(SettingOption("Custom (${current.deviceId})", current.deviceId))
                        }
                        add(SettingOption("Custom…", CUSTOM_SETTING_VALUE))
                    }
                SettingsDropdown(
                    label = "Device ID",
                    currentValue = current.deviceId,
                    options = deviceOptions,
                    tip =
                        "The production credential is bound to nfa-primary-phone. Custom IDs require " +
                            "matching server credentials.",
                    onSelected = { selected ->
                        if (selected == CUSTOM_SETTING_VALUE) {
                            showCustomDeviceId = true
                        } else {
                            draft = current.copy(deviceId = selected)
                            showCustomDeviceId = false
                        }
                    },
                    modifier = Modifier.testTag("device-id-form"),
                )
            }
            if (showCustomDeviceId) {
                item {
                    OutlinedTextField(
                        current.deviceId,
                        { draft = current.copy(deviceId = it) },
                        label = { Text("Custom device ID") },
                        supportingText = { Text("Tip: Save only after the gateway credential uses this exact ID.") },
                        modifier = Modifier.fillMaxWidth().testTag("custom-device-id-editor"),
                    )
                }
            }
            item {
                SettingsDropdown(
                    label = "Connect timeout",
                    currentValue = current.connectTimeoutMs,
                    options = durationOptions(10_000L, 15_000L, 30_000L, 60_000L),
                    tip = "How long to wait while opening the HTTPS connection. Default: 15 seconds.",
                    onSelected = { draft = current.copy(connectTimeoutMs = it) },
                )
            }
            item {
                SettingsDropdown(
                    label = "Read timeout",
                    currentValue = current.readTimeoutMs,
                    options = durationOptions(15_000L, 30_000L, 60_000L, 120_000L),
                    tip = "How long to wait for the gateway response. Default: 30 seconds.",
                    onSelected = { draft = current.copy(readTimeoutMs = it) },
                )
            }
            item {
                SettingsDropdown(
                    label = "Initial retry backoff",
                    currentValue = current.initialBackoffMs,
                    options = durationOptions(30_000L, 60_000L, 300_000L, 900_000L),
                    tip = "First delay after an offline or retryable failure. Queue order remains fixed.",
                    onSelected = { draft = current.copy(initialBackoffMs = it) },
                )
            }
            item {
                SettingsDropdown(
                    label = "Maximum retry backoff",
                    currentValue = current.maxBackoffMs,
                    options = durationOptions(1_800_000L, 3_600_000L, 21_600_000L),
                    tip = "Caps exponential backoff. Default: 6 hours; durable rows remain until delivered.",
                    onSelected = { draft = current.copy(maxBackoffMs = it) },
                )
            }
            item {
                SettingsDropdown(
                    label = "Sent-history retention",
                    currentValue = current.sentDays,
                    options = dayOptions(2, 7, 30, 90, 365),
                    tip =
                        "Applies only after SENT. Pending, retry, auth-paused, blocked, and quarantined outbox " +
                            "rows are never pruned, so 48-hour-plus outages remain protected.",
                    onSelected = { draft = current.copy(sentDays = it) },
                )
            }
            item {
                SettingsDropdown(
                    label = "Maximum stored SENT rows",
                    currentValue = current.maxSentRows,
                    options = countOptions(1_000, 5_000, 10_000, 50_000),
                    tip = "Bounds delivered history only; it never removes undelivered work.",
                    onSelected = { draft = current.copy(maxSentRows = it) },
                )
            }
            item {
                SettingsDropdown(
                    label = "Diagnostics retention",
                    currentValue = current.diagnosticsDays,
                    options = dayOptions(2, 7, 14, 30, 90),
                    tip = "Keeps bounded secret-free operational diagnostics.",
                    onSelected = { draft = current.copy(diagnosticsDays = it) },
                )
            }
            item {
                SettingsDropdown(
                    label = "Maximum diagnostics rows",
                    currentValue = current.diagnosticsMaxRows,
                    options = countOptions(500, 1_000, 2_000, 5_000),
                    tip = "Limits only diagnostics, never captured notifications or the outbox.",
                    onSelected = { draft = current.copy(diagnosticsMaxRows = it) },
                )
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
        item {
            TextButton(onClick = { showAdvancedJson = !showAdvancedJson }) {
                Text(if (showAdvancedJson) "Hide advanced JSON" else "Advanced JSON…")
            }
        }
        if (showAdvancedJson) {
            item {
                Text("Tip: Advanced JSON preserves full power but rejects secrets and invalid configuration.")
            }
            item {
                OutlinedTextField(
                    value = editor,
                    onValueChange = { editor = it },
                    label = { Text("Advanced JSON configuration") },
                    modifier = Modifier.fillMaxWidth().testTag("json-config-editor"),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(RgdsTheme.spacing.metadataGap)) {
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
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(RgdsTheme.spacing.metadataGap)) {
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
            replacing = snapshot.readiness.bearerSaved,
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

private const val CUSTOM_SETTING_VALUE = "__custom__"

private data class SettingOption(
    val label: String,
    val value: String,
)

@Composable
private fun SettingsDropdown(
    label: String,
    currentValue: String,
    options: List<SettingOption>,
    tip: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.value == currentValue }?.label ?: currentValue
    RgdsCard(
        title = label,
        subtitle = currentLabel,
        badgeLabel = label.take(2),
    ) {
        Text("Tip: $tip", style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = { expanded = true },
            modifier = modifier.fillMaxWidth().sizeIn(minHeight = RgdsTheme.spacing.buttonXl),
        ) {
            Text("Change $label")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        onSelected(option.value)
                    },
                )
            }
        }
    }
}

private fun durationOptions(vararg values: Long): List<SettingOption> =
    values.map { value -> SettingOption(formatDuration(value), value.toString()) }

private fun dayOptions(vararg values: Int): List<SettingOption> =
    values.map { value -> SettingOption(if (value == 1) "1 day" else "$value days", value.toString()) }

private fun countOptions(vararg values: Int): List<SettingOption> =
    values.map { value -> SettingOption("${"%,d".format(value)} rows", value.toString()) }

private fun formatDuration(milliseconds: Long): String =
    when {
        milliseconds % 3_600_000L == 0L -> "${milliseconds / 3_600_000L} hour(s)"
        milliseconds % 60_000L == 0L -> "${milliseconds / 60_000L} minute(s)"
        else -> "${milliseconds / 1_000L} seconds"
    }

private fun EndpointProfileDraft.displayName(): String = if (name == "tailscale") "Tailscale (production)" else name

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

private const val EXPECTED_BEARER_LENGTH = 43
private const val MAX_BEARER_INPUT_LENGTH = 128
private val BEARER_SHAPE = Regex("[A-Za-z0-9_-]{$EXPECTED_BEARER_LENGTH}")

internal fun bearerValidationError(value: String): String? =
    when {
        value.isEmpty() -> {
            "Paste the token before saving."
        }

        value.length != EXPECTED_BEARER_LENGTH -> {
            "Token must be exactly $EXPECTED_BEARER_LENGTH characters. Current length: ${value.length}."
        }

        !BEARER_SHAPE.matches(value) -> {
            "Token may contain only letters, numbers, hyphens, and underscores."
        }

        else -> {
            null
        }
    }

private fun CollectorUiSnapshot.tokenStatusLabel(): String =
    when {
        !readiness.bearerSaved -> {
            "Token not saved"
        }

        !bearerRevisionOk -> {
            "Token saved · revision check pending"
        }

        bearerSavedAtEpochMillis == null -> {
            "Token saved · revision OK"
        }

        else -> {
            val formatted =
                DateTimeFormatter
                    .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(bearerSavedAtEpochMillis))
            "Token saved · last updated $formatted · revision OK"
        }
    }

@Composable
private fun TokenEntryDialog(
    repository: CollectorUiRepository,
    replacing: Boolean,
    dismiss: () -> Unit,
    onSaved: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf("") }
    var tokenError by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    SecureWindowEffect()
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (replacing) "Replace saved token" else "Enter bearer token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(RgdsTheme.spacing.metadataGap)) {
                Text(
                    if (replacing) {
                        "The saved token stays active unless this replacement saves successfully."
                    } else {
                        "Paste the 43-character token issued for this device."
                    },
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it.take(MAX_BEARER_INPUT_LENGTH)
                        tokenError = ""
                    },
                    label = { Text("Bearer token") },
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                        ),
                    trailingIcon = {
                        TextButton(onClick = { showToken = !showToken }) {
                            Text(if (showToken) "Hide token" else "Show token")
                        }
                    },
                    supportingText = {
                        Column {
                            Text("Length: ${token.length} / $EXPECTED_BEARER_LENGTH")
                            if (tokenError.isNotBlank()) Text(tokenError)
                        }
                    },
                    modifier = Modifier.testTag("bearer-token-editor"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val validationError = bearerValidationError(token)
                    if (validationError != null) {
                        tokenError = validationError
                        return@TextButton
                    }
                    val transient = token.toCharArray()
                    saving = true
                    scope.launch {
                        val outcome =
                            try {
                                repository.saveToken(transient)
                            } finally {
                                transient.fill('\u0000')
                            }
                        saving = false
                        if (outcome.saved) {
                            token = ""
                            onSaved(outcome.safeWarning)
                        } else {
                            tokenError = "Token could not be saved. The previous saved token is unchanged."
                        }
                    }
                },
                enabled = !saving,
            ) { Text(if (saving) "Saving…" else "Save token") }
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
