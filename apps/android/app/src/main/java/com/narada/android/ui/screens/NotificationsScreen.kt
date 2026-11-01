package com.narada.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.narada.android.ui.*

@Composable
fun NotificationsScreen(data: WorkspaceLoad.Ready, allowed: Boolean, busy: Boolean, actions: WorkspaceActions) {
    var clear by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Private notifications", style = MaterialTheme.typography.titleMedium)
                        Caption(if (!data.settings.notificationsEnabled) "Off. Enable only if you want local updates." else if (allowed) "Allowed by Android. Generic content only." else "Blocked by Android. The app still works.")
                    }
                    Switch(data.settings.notificationsEnabled, actions.notifications, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Enable private notifications" })
                }
                Caption("Enabling asks for Android permission where required. Notification text never includes a device name, filename, message, folder path or key.")
                OutlinedButton(actions.testNotification, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy && data.settings.notificationsEnabled && allowed) { Text("Send a generic test notification") }
                TextButton(actions.systemNotifications, Modifier.heightIn(min = 48.dp)) { Text("Open Android notification settings") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                SectionTitle("Local activity", Modifier.weight(1f))
                TextButton({ clear = true }, Modifier.heightIn(min = 48.dp), enabled = !busy && data.metadata.events.isNotEmpty()) { Text("Clear") }
            }
        }
        if (data.metadata.events.isEmpty()) item { Panel { Caption("Nothing recorded. This is not a message inbox; only actual local setup and diagnostic activity appears here.") } }
        items(data.metadata.events, key = { it.id }) { ActivityRow(it) }
        item { Caption("Showing up to 30 recent local events. The metadata ledger retains at most 200. Exported reports do not include these event contents.") }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear local activity?") }, text = { Text("This clears only the event list. Settings, folder grants and the last local-check result are kept.") },
        confirmButton = { TextButton({ actions.clearActivity(); clear = false }) { Text("Clear activity") } }, dismissButton = { TextButton({ clear = false }) { Text("Keep activity") } })
}
