package com.narada.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.narada.android.R
import com.narada.android.ui.*
import com.narada.domain.*

@Composable
fun DiagnosticsScreen(data: WorkspaceLoad.Ready, busy: Boolean, actions: WorkspaceActions) {
    val job = data.metadata.job
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Text("A small check.\nA clearer picture.", style = MaterialTheme.typography.headlineLarge) }
        item { Caption("Check local storage, preferences and saved permissions. No network access or folder-content scan.") }
        item {
            Panel {
                TileIcon(R.drawable.ic_diagnostics)
                SectionTitle(when (job?.state) {
                    null -> "Not run yet"
                    LocalJobState.QUEUED -> "Waiting for Android"
                    LocalJobState.RUNNING -> "Local check active"
                    LocalJobState.COMPLETED -> "Last local check completed"
                    LocalJobState.CANCELLED -> "Last local check cancelled"
                    LocalJobState.FAILED -> "Last local check needs attention"
                })
                Caption(when (job?.state) {
                    LocalJobState.COMPLETED -> "Local checks completed. This does not certify connections, transfers or security."
                    LocalJobState.FAILED -> "A check or scheduling step failed. No data reset was attempted."
                    LocalJobState.QUEUED, LocalJobState.RUNNING -> "Android controls scheduling and may pause this check."
                    else -> "One check at a time. Leaving this screen does not start another."
                })
                if (job?.state?.active == true) OutlinedButton(actions.cancelCheck, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy) { Text("Cancel local check") }
                else PrimaryAction("Run local check", actions.requestCheck, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
        item { InfoPanel("No always-on service", "Android may defer or stop checks. After force-stop, open NARADA to resume.") }
        item { MenuRow("Review support report", "Inspect a small, allowlisted JSON report before sharing", R.drawable.ic_share, actions.inspectReport) }
        item { Caption("Reports exclude names, URIs, paths, endpoints, account IDs, message content, keys and database dumps. Sharing grants read access to one report URI only.") }
    }
}

@Composable
fun HelpScreen(navigate: (Destination) -> Unit) {
    val steps = listOf(
        "01" to "Connect" to "Use the same trusted network. In Devices, scan your computer’s QR.",
        "02" to "Verify and approve" to "Compare full fingerprints, approve on the computer, then finish on this phone.",
        "03" to "Chat or share" to "Allow chat separately. Folder sharing is optional and managed on the computer.",
    )
    LazyColumn(Modifier.fillMaxSize().testTag("screen_help"), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Eyebrow("NARADA FOR ANDROID"); Spacer(Modifier.height(12.dp)); Text("Connect. Chat. Share.", style = MaterialTheme.typography.headlineLarge) }
        steps.forEach { (heading, description) -> item {
            Panel { Eyebrow(heading.first); Text(heading.second, style = MaterialTheme.typography.titleMedium); Caption(description) }
        } }
        item { MenuRow("Diagnostics", "Local checks and a support report", R.drawable.ic_diagnostics, { navigate(Destination.DIAGNOSTICS) }) }
        item { InfoPanel("Keep your data", "If storage cannot be opened, retry without resetting. Do not uninstall or clear app data to fix it.") }
        item { InfoPanel("Development build", "Physical-device testing, independent security review and safe-upgrade checks remain open.", R.drawable.ic_phone) }
    }
}
