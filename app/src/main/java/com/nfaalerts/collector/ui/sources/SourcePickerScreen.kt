package com.nfaalerts.collector.ui.sources

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nfaalerts.collector.capture.RawTextField
import com.nfaalerts.collector.config.InstalledApp
import com.nfaalerts.collector.config.InstalledAppClassifier
import com.nfaalerts.collector.config.SelectionUpdate
import com.nfaalerts.collector.config.SourceSelection
import com.nfaalerts.collector.config.SourceSelectionRepository
import kotlinx.coroutines.launch

@Composable
fun SourcePickerScreen(
    apps: List<InstalledApp>,
    repository: SourceSelectionRepository,
    sourceIdForPackage: (String) -> String,
    modifier: Modifier = Modifier,
) {
    val snapshot by repository.selections.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var mutationError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingBnn by remember { mutableStateOf<InstalledApp?>(null) }
    var editing by remember { mutableStateOf<SourceSelection?>(null) }
    val visible =
        InstalledAppClassifier.visibleApps(
            apps = apps,
            showSystemApps = showSystemApps,
            selectedPackages = snapshot.packageNames,
            query = query,
        )

    LazyColumn(
        modifier = modifier.padding(16.dp).testTag("sources-list"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                "Sources (${snapshot.selections.size} / 10)",
                modifier = Modifier.semantics { heading() },
            )
        }
        item { Text("${snapshot.selections.size} / 10") }
        item {
            Text(
                "Non-BNN sources are captured locally as BLOCKED_CONTRACT until the gateway contract is approved.",
            )
        }
        mutationError?.let { message -> item { Text(message) } }
        item { Text("Selected source editor") }
        items(snapshot.selections, key = { "selected-${it.packageName}" }) { source ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${source.appLabel} (${source.packageName}) — ${if (source.enabled) "Enabled" else "Disabled"}; ${source.sourceId}",
                )
                Text("Raw priority: ${source.rawTextOrder.joinToString { it.configValue }}")
                TextButton(
                    onClick = { editing = source },
                    modifier = Modifier.testTag("edit-source-${source.packageName}"),
                ) { Text("Edit source") }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search apps") },
                modifier = Modifier.fillMaxWidth().testTag("source-search"),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show system apps")
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = showSystemApps,
                    onCheckedChange = { showSystemApps = it },
                    modifier = Modifier.testTag("show-system-apps"),
                )
            }
        }
        item { Text("Installed app picker") }
        items(visible, key = { "picker-${it.packageName}" }) { app ->
            val selected = app.packageName in snapshot.packageNames
            SourcePickerRow(
                app = app,
                selected = selected,
                onToggle = {
                    if (selected) {
                        scope.launch { mutationError = repository.remove(app.packageName).safeError() }
                    } else {
                        val sourceId = sourceIdForPackage(app.packageName)
                        if (sourceId == "bnn") {
                            pendingBnn = app
                        } else {
                            scope.launch {
                                mutationError =
                                    repository
                                        .upsert(
                                            app.selection(sourceId, false),
                                        ).safeError()
                            }
                        }
                    }
                },
            )
        }
    }

    editing?.let { source ->
        SourceEditorDialog(
            source = source,
            save = repository::upsert,
            accepted = {
                mutationError = null
                editing = null
            },
            dismiss = { editing = null },
        )
    }
    pendingBnn?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingBnn = null },
            title = { Text("Confirm BNN source") },
            text = {
                Column {
                    Text("Confirm that ${app.label} is the BNN application.")
                    mutationError?.let { Text(it) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val result = repository.upsert(app.selection("bnn", true))
                            mutationError = result.safeError()
                            if (result is SelectionUpdate.Accepted) pendingBnn = null
                        }
                    },
                    modifier = Modifier.testTag("confirm-bnn-source"),
                ) { Text("Confirm") }
            },
            dismissButton = { Button(onClick = { pendingBnn = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SourceEditorDialog(
    source: SourceSelection,
    save: suspend (SourceSelection) -> SelectionUpdate,
    accepted: () -> Unit,
    dismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var enabled by remember(source) { mutableStateOf(source.enabled) }
    var sourceId by remember(source) { mutableStateOf(source.sourceId) }
    var bnnConfirmed by remember(source) { mutableStateOf(source.bnnMappingConfirmed) }
    var rawOrder by remember(source) { mutableStateOf(source.rawTextOrder) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Edit ${source.appLabel}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled")
                    Switch(enabled, { enabled = it })
                }
                OutlinedTextField(
                    sourceId,
                    { sourceId = it },
                    label = { Text("Source ID") },
                    modifier = Modifier.testTag("source-id-editor"),
                )
                if (sourceId == "bnn") {
                    Text("Confirm this exact app is BNN before it may use the BNN contract.")
                    Switch(bnnConfirmed, { bnnConfirmed = it }, modifier = Modifier.testTag("confirm-bnn-editor"))
                }
                Text("Ordered raw-text priority")
                rawOrder.forEachIndexed { index, field ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(field.configValue, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { rawOrder = rawOrder.move(index, index - 1) },
                            enabled = index > 0,
                            modifier = Modifier.testTag("raw-up-${field.configValue}"),
                        ) { Text("Up") }
                        TextButton(
                            onClick = { rawOrder = rawOrder.move(index, index + 1) },
                            enabled = index < rawOrder.lastIndex,
                            modifier = Modifier.testTag("raw-down-${field.configValue}"),
                        ) { Text("Down") }
                        TextButton(
                            onClick = { rawOrder = rawOrder - field },
                            enabled = rawOrder.size > 1,
                            modifier = Modifier.testTag("raw-remove-${field.configValue}"),
                        ) { Text("Remove") }
                    }
                }
                RawTextField.entries.filterNot(rawOrder::contains).forEach { field ->
                    TextButton(
                        onClick = { rawOrder = rawOrder + field },
                        modifier = Modifier.testTag("raw-add-${field.configValue}"),
                    ) { Text("Add ${field.configValue}") }
                }
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val result =
                        save(
                            source.copy(
                                enabled = enabled,
                                sourceId = sourceId,
                                bnnMappingConfirmed = sourceId == "bnn" && bnnConfirmed,
                                rawTextOrder = rawOrder,
                            ),
                        )
                    error = result.safeError()
                    if (result is SelectionUpdate.Accepted) accepted()
                }
            }) { Text("Save source") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SourcePickerRow(
    app: InstalledApp,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        app.icon?.let { image ->
            Image(bitmap = image, contentDescription = "${app.label} icon", modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label)
            Text(app.packageName)
        }
        Checkbox(
            checked = selected,
            onCheckedChange = { onToggle() },
            modifier = Modifier.testTag("source-toggle-${app.packageName}"),
        )
    }
}

private fun SelectionUpdate.safeError(): String? =
    when (this) {
        SelectionUpdate.Accepted -> null
        SelectionUpdate.BnnConfirmationRequired -> "Explicit BNN confirmation is required."
        SelectionUpdate.DuplicateSource -> "That package is already selected."
        is SelectionUpdate.InvalidSource -> "Source is invalid. Check every field and raw-text priority."
        is SelectionUpdate.MaximumReached -> "Maximum $maximum sources"
        SelectionUpdate.PersistenceFailure -> "Source change could not be persisted."
    }

private fun List<RawTextField>.move(
    from: Int,
    to: Int,
): List<RawTextField> {
    if (from !in indices || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

private fun InstalledApp.selection(
    sourceId: String,
    confirmed: Boolean,
) = SourceSelection(
    packageName = packageName,
    appLabel = label,
    sourceId = sourceId,
    enabled = true,
    bnnMappingConfirmed = confirmed,
    rawTextOrder = RawTextField.DEFAULT_ORDER,
)
