package com.narada.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.narada.android.R
import com.narada.android.ui.*
import com.narada.domain.*

@Composable
fun SettingsScreen(data: WorkspaceLoad.Ready, busy: Boolean, allowed: Boolean, actions: WorkspaceActions, navigate: (Destination) -> Unit, draftName: String?) {
    val name = draftName ?: data.settings.deviceName
    val valid = runCatching { DeviceNamePolicy.normalize(name) }.isSuccess
    val dirty = name != data.settings.deviceName
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("screen_settings"), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Caption("Small preferences. A workspace that feels like yours.") }
        item {
            Panel {
                SectionTitle("This phone")
                OutlinedTextField(name, actions.editName, Modifier.fillMaxWidth(), label = { Text("Device name") }, singleLine = true, isError = !valid,
                    supportingText = { Text(if (valid) "A local label, not an account or pairing identity." else "Use 1–48 characters without control characters.") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), enabled = !busy)
                PrimaryAction("Save name", { actions.saveName(name) }, Modifier.fillMaxWidth(), enabled = dirty && valid && !busy)
            }
        }
        item { MenuRow("Appearance", "Light theme · Motion preference", R.drawable.ic_appearance, { navigate(Destination.APPEARANCE) }) }
        item { MenuRow("Local storage", "${data.metadata.grants.size} retained folder grants · No file scan", R.drawable.ic_folder, { navigate(Destination.STORAGE) }) }
        item { MenuRow("Notifications", if (!data.settings.notificationsEnabled) "Off · Optional, private updates" else if (allowed) "On · Generic content only" else "Blocked by Android", R.drawable.ic_bell, { navigate(Destination.NOTIFICATIONS) }) }
        item { MenuRow("Diagnostics", "Run a local check or review a redacted report", R.drawable.ic_diagnostics, { navigate(Destination.DIAGNOSTICS) }) }
        item { InfoPanel("Non-secret preferences only", "Settings and grant metadata are app-private, not end-to-end encrypted. Sensitive-content storage remains unavailable until its dedicated security phase.") }
        item { Caption("NARADA 0.1.0 · Development build\nNo account, background socket or cloud connection.") }
    }
}

@Composable
fun AppearanceScreen(data: WorkspaceLoad.Ready, busy: Boolean, actions: WorkspaceActions) {
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Text("Quiet by design.", style = MaterialTheme.typography.headlineLarge); Spacer(Modifier.height(8.dp)); Caption("Monochrome surfaces, readable type and a little breathing room.") }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = true, onClick = null); Column { Text("Light", style = MaterialTheme.typography.titleMedium); Caption("The supported Phase 1 theme") }
                }
                HorizontalDivider(color = NaradaColors.Border)
                Caption("Dark and follow-system themes are not implemented in this phase. No non-working theme switches are shown.")
            }
        }
        item {
            Panel {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { Text("Reduce motion", style = MaterialTheme.typography.titleMedium); Caption("Remove navigation fade transitions.") }
                    Switch(data.settings.reduceMotion, actions.reduceMotion, enabled = !busy, modifier = Modifier.testTag("reduce_motion").semantics { contentDescription = "Reduce navigation motion" })
                }
            }
        }
        item { Caption("Text follows Android’s font-size setting. There is no custom font download.") }
    }
}

@Composable
fun StorageScreen(data: WorkspaceLoad.Ready, busy: Boolean, actions: WorkspaceActions) {
    var removeId by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Only what\nyou choose.", style = MaterialTheme.typography.headlineLarge) }
        item { Caption("Retain read permission for up to eight folders using Android’s own picker. Phase 1 does not browse, index, upload or copy their contents.") }
        item { PrimaryAction("Choose a folder", actions.pickFolder, Modifier.fillMaxWidth(), enabled = !busy && data.metadata.grants.size < FoundationLimits.MAX_GRANTS) }
        item { SectionTitle("Retained folder permissions") }
        if (data.metadata.grants.isEmpty()) item { Panel { Glyph(R.drawable.ic_folder); Text("No folder access yet", style = MaterialTheme.typography.titleMedium); Caption("Your phone’s files are not shared just because NARADA is installed.") } }
        items(data.metadata.grants, key = { it.id }) { grant ->
            Panel {
                Text(grant.label, style = MaterialTheme.typography.titleMedium)
                Caption(if (grant.available) "Read permission retained · Not proof of provider availability" else "Permission missing · Choose this folder again to restore access")
                TextButton({ removeId = grant.id }, Modifier.heightIn(min = 48.dp), enabled = !busy) { Text("Remove permission") }
            }
        }
        item { InfoPanel("No broad storage access", "Write permission is not retained in Phase 1—even when a provider offers it. Persisted permission is checked again on return to the app. Cloud or virtual folders may still be unavailable.", R.drawable.ic_lock) }
        item { Caption("Internal settings, metadata and temporary check files stay in the app sandbox. Android backup and device-transfer rules exclude app data; OEM behavior still needs device qualification.") }
    }
    if (removeId != null) AlertDialog(onDismissRequest = { removeId = null }, title = { Text("Remove folder access?") }, text = { Text("NARADA will release its retained read permission. No files in the selected folder will be deleted.") },
        confirmButton = { TextButton({ removeId?.let(actions.forgetFolder); removeId = null }) { Text("Remove permission") } },
        dismissButton = { TextButton({ removeId = null }) { Text("Keep access") } })
}
