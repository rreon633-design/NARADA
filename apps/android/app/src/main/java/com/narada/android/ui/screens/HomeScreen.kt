package com.narada.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.narada.android.R
import com.narada.android.connection.ConnectionsUiState
import com.narada.connection.TrustStatus
import com.narada.android.ui.*
import com.narada.domain.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(data: WorkspaceLoad.Ready, navigate: (Destination) -> Unit, connections: ConnectionsUiState = ConnectionsUiState(loading = false)) {
    val paired = connections.document?.peers?.count { it.status == TrustStatus.ACTIVE } ?: 0
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow("YOUR PERSONAL WORKSPACE")
                Text("A little closer.\nA lot more yours.", style = MaterialTheme.typography.headlineLarge)
            }
        }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = NaradaColors.Ink) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(R.drawable.ic_devices, tint = Color.White)
                        Eyebrow(if (connections.error != null) "CONNECTIONS NEED ATTENTION" else if (connections.loading) "READING PROTECTED TRUST" else if (connections.connectedPeer != null) "AUTHENTICATED CONNECTION" else if (paired > 0) "$paired SAVED DEVICE${if (paired > 1) "S" else ""}" else "NO PAIRED DEVICES", color = NaradaColors.OnDarkMuted)
                    }
                    Text("Everything starts\nwith this phone.", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                    Text(if (connections.connectedPeer != null) "Your saved device answered an authenticated health check. Files rechecks its grants before browsing; a connection alone does not authorize content." else if (paired > 0) "Trust is saved on this phone. Open Devices to review or reconnect; no online status is inferred." else "Pair your computer with a real invitation. Your phone’s transport key stays in AndroidKeyStore.", color = NaradaColors.OnDarkMuted, style = MaterialTheme.typography.bodyMedium)
                    Button({ navigate(Destination.DEVICES) }, Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = NaradaColors.Ink), shape = RoundedCornerShape(13.dp)) {
                        Text("View devices"); Spacer(Modifier.width(8.dp)); Glyph(R.drawable.ic_right)
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionTitle("Your workspace")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickCard(Destination.FILES, "Granted desktop roots", Modifier.weight(1f), navigate)
                    QuickCard(Destination.CHAT, "Protected foreground text", Modifier.weight(1f), navigate)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuickCard(Destination.TRANSFERS, "Verified private downloads", Modifier.weight(1f), navigate)
                    QuickCard(Destination.SYNC, "Scoped one-way refresh", Modifier.weight(1f), navigate)
                }
            }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { SectionTitle("On this phone"); DeviceCard(data.settings.deviceName) { navigate(Destination.SETTINGS) } } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Recent activity", Modifier.weight(1f))
                TextButton({ navigate(Destination.NOTIFICATIONS) }, Modifier.heightIn(min = 48.dp)) { Text("View all") }
            }
        }
        if (data.metadata.events.isEmpty()) item { Panel { Caption("Nothing recorded yet. Real local activity will appear here.") } }
        items(data.metadata.events.take(3), key = { it.id }) { ActivityRow(it) }
        item { Caption("Phases 6–7 development build · Encrypted attachments and retained offline copies") }
    }
}
@Composable
private fun QuickCard(destination: Destination, subtitle: String, modifier: Modifier, navigate: (Destination) -> Unit) {
    Surface({ navigate(destination) }, modifier.heightIn(min = 154.dp), shape = RoundedCornerShape(19.dp), color = Color.White, border = BorderStroke(1.dp, NaradaColors.Border)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TileIcon(destination.icon()); Text(destination.label, style = MaterialTheme.typography.titleMedium); Caption(subtitle)
        }
    }
}
@Composable
fun ActivityRow(activity: LocalActivity) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        TileIcon(if (activity.kind.name.startsWith("CHECK")) R.drawable.ic_diagnostics else R.drawable.ic_check)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(activity.kind.title(), style = MaterialTheme.typography.bodyLarge)
            Caption(Instant.ofEpochMilli(activity.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM · HH:mm")))
        }
    }
}
fun ActivityKind.title(): String = when (this) {
    ActivityKind.SETUP_COMPLETED -> "This phone was set up"
    ActivityKind.SETTINGS_SAVED -> "Local settings updated"
    ActivityKind.FOLDER_RETAINED -> "Folder read permission retained"
    ActivityKind.FOLDER_FORGOTTEN -> "Folder permission removed"
    ActivityKind.CHECK_QUEUED -> "Local check requested"
    ActivityKind.CHECK_COMPLETED -> "Local workspace check completed"
    ActivityKind.CHECK_CANCELLED -> "Local check cancelled"
    ActivityKind.CHECK_FAILED -> "Local check needs attention"
    ActivityKind.NOTIFICATION_SENT -> "Notification handed to Android"
    ActivityKind.NOTIFICATION_UNAVAILABLE -> "Notification was unavailable"
}
