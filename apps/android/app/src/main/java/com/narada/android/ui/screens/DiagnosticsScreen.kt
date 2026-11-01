package com.narada.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.narada.android.R
import com.narada.android.ui.*
import com.narada.domain.*

@Composable
fun DiagnosticsScreen(data: WorkspaceLoad.Ready, busy: Boolean, actions: WorkspaceActions) {
    val job = data.metadata.job
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Text("A small check.\nA clearer picture.", style = MaterialTheme.typography.headlineLarge) }
        item { Caption("Check the local metadata, preferences, a small app-private file and retained permission records. No network access and no folder-content scan.") }
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
                    LocalJobState.COMPLETED -> "The local checks completed at the recorded time. This is not a connection, transfer or security certification."
                    LocalJobState.FAILED -> "A check or scheduling step failed. No data reset was attempted."
                    LocalJobState.QUEUED, LocalJobState.RUNNING -> "This is the durable operation status. Android controls scheduling; it is not a live percentage or a promise of continuous execution."
                    else -> "One durable request at a time. Leaving this screen does not create a second job."
                })
                if (job?.state?.active == true) OutlinedButton(actions.cancelCheck, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !busy) { Text("Cancel local check") }
                else PrimaryAction("Run local check", actions.requestCheck, Modifier.fillMaxWidth(), enabled = !busy)
            }
        }
        item { InfoPanel("No always-on service", "Checks use bounded WorkManager work. Android can defer or stop it. Force-stop pauses work until the app is explicitly opened again.") }
        item { MenuRow("Review support report", "Inspect a small, allowlisted JSON report before sharing", R.drawable.ic_share, actions.inspectReport) }
        item { Caption("Reports exclude names, URIs, paths, endpoints, account IDs, message content, keys and database dumps. Sharing grants read access to one report URI only.") }
    }
}

@Composable
fun HelpScreen(navigate: (Destination) -> Unit) {
    val phases = listOf(
        "01" to "Native foundations · This build", "02" to "Identity and real device pairing",
        "03" to "Authorized remote files", "04" to "Verified, resumable transfers", "05" to "Durable encrypted messaging",
        "06" to "Encrypted attachments", "07" to "Offline pins and one-way refresh", "08" to "Native qualification and review", "09" to "Signing, pilot and release",
    )
    LazyColumn(Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item { Eyebrow("NARADA FOR ANDROID"); Spacer(Modifier.height(12.dp)); Text("Built in the open.\nOne phase at a time.", style = MaterialTheme.typography.headlineLarge) }
        item { Caption("Phase 1 is a native development foundation, not a replacement for the working desktop protocol or a production-qualified mobile client.") }
        item { MenuRow("Diagnostics", "Local checks and a reviewable support report", R.drawable.ic_diagnostics, { navigate(Destination.DIAGNOSTICS) }) }
        item { SectionTitle("The development roadmap") }
        phases.forEach { (number, title) -> item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Eyebrow(number); Text(title, style = MaterialTheme.typography.bodyLarge) } } }
        item { InfoPanel("If storage cannot be opened", "NARADA does not silently replace corrupt settings, delete a damaged database or recreate a database whose enrollment marker remains. Retry reads only. Do not uninstall or clear app data if you need to preserve it.") }
        item { InfoPanel("What this build cannot prove", "A host test is not a physical Android test. Provider grants, system notifications, TalkBack, background limits and actual device restart behavior need native qualification before release.", R.drawable.ic_phone) }
        item { Caption("No account or network permission in Phase 1. Light theme only. Android 10+ development baseline. Detailed phase and test records are included with the source handoff.") }
    }
}
