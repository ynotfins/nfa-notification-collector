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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
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
    val snapshot by repository.selections.collectAsState()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var showSystemApps by rememberSaveable { mutableStateOf(false) }
    var maximumReached by rememberSaveable { mutableStateOf(false) }
    var pendingBnn by remember { mutableStateOf<InstalledApp?>(null) }
    val visible =
        InstalledAppClassifier.visibleApps(
            apps = apps,
            showSystemApps = showSystemApps,
            selectedPackages = snapshot.packageNames,
            query = query,
        )

    Column(modifier = modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${snapshot.selections.size} / 10")
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search apps") },
            modifier = Modifier.fillMaxWidth().testTag("source-search"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show system apps")
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = showSystemApps,
                onCheckedChange = { showSystemApps = it },
                modifier = Modifier.testTag("show-system-apps"),
            )
        }
        if (maximumReached) Text("Maximum 10 sources")
        visible.forEach { app ->
            val selected = app.packageName in snapshot.packageNames
            SourcePickerRow(
                app = app,
                selected = selected,
                onToggle = {
                    if (selected) {
                        scope.launch {
                            repository.remove(app.packageName)
                            maximumReached = false
                        }
                    } else {
                        val sourceId = sourceIdForPackage(app.packageName)
                        if (sourceId == "bnn") {
                            pendingBnn = app
                        } else {
                            scope.launch {
                                maximumReached =
                                    repository.upsert(app.selection(sourceId, false)) is SelectionUpdate.MaximumReached
                            }
                        }
                    }
                },
            )
        }
    }

    pendingBnn?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingBnn = null },
            title = { Text("Confirm BNN source") },
            text = { Text("Confirm that ${app.label} is the BNN application.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            maximumReached =
                                repository.upsert(app.selection("bnn", true)) is SelectionUpdate.MaximumReached
                            pendingBnn = null
                        }
                    },
                    modifier = Modifier.testTag("confirm-bnn-source"),
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                Button(onClick = { pendingBnn = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SourcePickerRow(
    app: InstalledApp,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        app.icon?.let { drawable ->
            val image = remember(drawable) { drawable.toBitmap(32, 32).asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "${app.label} icon",
                modifier = Modifier.size(24.dp),
            )
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
