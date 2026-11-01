package com.narada.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.narada.android.chat.chatAccess
import com.narada.android.connection.ConnectionsUiState
import com.narada.android.offline.*
import com.narada.android.transfers.transferMessage
import com.narada.android.ui.*
import com.narada.connection.*
import java.time.Instant

data class OfflineActions(
    val visible: (Boolean) -> Unit = {}, val reload: () -> Unit = {}, val select: (String, String) -> Unit = { _, _ -> },
    val refresh: (Boolean) -> Unit = {}, val stop: () -> Unit = {}, val pin: (String, Boolean) -> Unit = { _, _ -> },
    val refreshPin: (String, Boolean) -> Unit = { _, _ -> }, val adopt: (String, Boolean) -> Unit = { _, _ -> },
    val release: (String, String, Boolean) -> Unit = { _, _, _ -> }, val unpin: (String, Boolean) -> Unit = { _, _ -> }, val forget: (Boolean) -> Unit = {},
    val openTransfers: () -> Unit = {},
)
private data class OfflineReview(val kind: String, val id: String? = null, val download: String? = null)
@Composable fun OfflineScreen(state: OfflineUiState, connections: ConnectionsUiState, actions: OfflineActions) {
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        DisposableEffect(Unit) { actions.visible(true); onDispose { actions.visible(false) } }
        val safe = state.visible && connections.foreground && state.access != null && state.access == chatAccess(connections, state.peerId)
        key(safe, state.viewEpoch, state.peerId, state.rootId) { OfflineContent(state, safe, connections, actions) }
    }
}
@Composable private fun OfflineContent(state: OfflineUiState, safe: Boolean, connections: ConnectionsUiState, actions: OfflineActions) {
    var menu by remember { mutableStateOf(false) }
    var review by remember { mutableStateOf<OfflineReview?>(null) }
    val network = safe && state.networkEligible && !state.busy
    val metadataReady = network && state.scopeCurrent && state.freshAt != null && state.partialCount == null && state.metadataError == null
    LazyColumn(Modifier.fillMaxSize().testTag("offline_pins"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("ONE-WAY · PRIVATE COPIES")
            Text("Keep a copy.\nNot a promise.", style = MaterialTheme.typography.headlineMedium)
            Caption("A fresh listing is not a downloaded file. Only a complete verified receipt makes a private copy available. Source deletion, failed refresh and restart do not erase your saved version.")
        } }
        if (!safe) item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoPanel("Private offline view is closed", "Load sealed metadata while NARADA is visible. No file names, cursor or selection are restored from Android saved instance state.")
            OutlinedButton(actions.reload, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = connections.foreground) { Text("Load protected pins") }
        } }
        state.error?.let { code -> item { InfoPanel("Needs attention", offlineMessage(code)) } }
        if (safe) {
            item { Box {
                OutlinedButton({ menu = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy && state.roots.isNotEmpty()) {
                    Text(state.roots.find { it.peerId == state.peerId && it.rootId == state.rootId }?.label ?: "No Read + SyncRead root")
                }
                DropdownMenu(menu, { menu = false }) { state.roots.forEach { r ->
                    DropdownMenuItem(text = { Text(r.label) }, onClick = { menu = false; actions.select(r.peerId, r.rootId) }, modifier = Modifier.heightIn(min = 48.dp))
                } }
            } }
            item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.busy) OutlinedButton(actions.stop, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Pause metadata refresh") }
                else Button({ actions.refresh(false) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = network && state.rootId != null) { Text(if (state.partialCount != null) "Continue scoped metadata" else "Refresh metadata only") }
                Caption(state.freshAt?.let { "Last completed metadata observation: ${Instant.ofEpochMilli(it)}. This is not a byte-availability check." } ?: "No completed metadata snapshot. Nothing is claimed to be saved.")
                if (state.partialCount != null) Caption("${state.partialCount} staged metadata rows · previous completed view retained · not published as a new snapshot")
                if (!state.scopeCurrent && state.freshAt != null) Caption("Saved metadata belongs to earlier authority. Refresh it before creating a new pin.")
                state.metadataError?.let { Caption(offlineMessage(it)) }
                TextButton({ actions.refresh(true) }, Modifier.heightIn(min = 48.dp), enabled = network) { Text("Request a new stable snapshot") }
                Caption("Two cached roots, 128 entries per root, four pins. Downloads share four slots and 128 MiB with retained private data. No silent eviction, reindex command or two-way filesystem sync.")
            } }
            item { Text("Pinned versions", style = MaterialTheme.typography.titleLarge) }
            if (state.pins.isEmpty()) item { InfoPanel("No pinned copies", "Refresh an authorized root, then explicitly pin an ordinary file below. NARADA does not add sample files or download bytes just because a listing is fresh.") }
            items(state.pins, key = { "pin-${it.id}" }) { p ->
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Caption(if (p.available) "Verified private version available" else "No currently verified pinned version")
                    if (p.tombstone) InfoPanel("Source removed", "The remote object is absent. Existing private bytes and previous versions are retained; no replacement is guessed from a matching path.")
                    if (p.update) Caption("Source version changed · your saved version has not been replaced")
                    if (p.oldPairing) Caption("Earlier pairing · saved bytes remain usable, refresh is blocked")
                    if (p.candidateId != null) {
                        Caption("Separate refresh candidate: ${p.candidateState?.name?.lowercase()?.replace('_', ' ') ?: "unavailable"}")
                        if (p.candidateState == DownloadState.VERIFIED) Button({ review = OfflineReview("adopt", p.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy) { Text("Verify & adopt this candidate") }
                        TextButton({ review = OfflineReview("release", p.id, p.candidateId) }, Modifier.heightIn(min = 48.dp), enabled = !state.busy && p.candidateState !in setOf(DownloadState.RUNNING, DownloadState.QUEUED)) { Text("Release candidate reference") }
                    } else OutlinedButton({ review = OfflineReview("refresh", p.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = metadataReady && !p.tombstone && !p.oldPairing && (p.update || !p.available)) { Text("Download a separate refresh candidate") }
                    p.previous.forEach { previous ->
                        Caption("Previous version still retained")
                        TextButton({ review = OfflineReview("release", p.id, previous) }, Modifier.heightIn(min = 48.dp), enabled = !state.busy) { Text("Release old reference · keep bytes") }
                    }
                    OutlinedButton(actions.openTransfers, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open copies & export controls") }
                    TextButton({ review = OfflineReview("unpin", p.id) }, Modifier.heightIn(min = 48.dp), enabled = !state.busy) { Text("Unpin · keep downloaded bytes") }
                } }
            }
            item { HorizontalDivider(); Text("Completed metadata listing", style = MaterialTheme.typography.titleLarge); Caption("Names and paths below are display metadata, never filesystem authority.") }
            items(state.entries, key = { "entry-${it.object_id}" }) { e ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.name, style = MaterialTheme.typography.titleMedium); Caption("${e.relative_path} · ${e.kind} · ${e.size} bytes")
                    if (e.kind == "file") OutlinedButton({ review = OfflineReview("pin", e.object_id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = metadataReady) { Text("Review & pin this version") }
                }
            }
            item { TextButton({ review = OfflineReview("forget") }, Modifier.heightIn(min = 48.dp), enabled = !state.busy && state.pins.isEmpty() && state.rootId != null) { Text("Forget unreferenced metadata only") } }
        }
    }
    val r = review
    if (r != null && safe) TransferConsentDialog(
        title = "Review one-way copy action",
        originalName = state.pins.find { it.id == r.id }?.name ?: state.entries.find { it.object_id == r.id }?.name ?: "Protected metadata",
        body = when (r.kind) {
            "pin" -> "Create a protected pin and a bounded download intent for this exact object and version. Bytes are fetched only with current Read + SyncRead authority. Metadata alone never makes it available."
            "refresh" -> "Download the currently observed source version into a new immutable candidate. The current and previous copies remain untouched if download or verification fails. Quota exhaustion does not evict them."
            "adopt" -> "Re-read, decrypt and hash the entire candidate before adopting it. The current version becomes an explicitly retained previous reference; it is not overwritten or deleted."
            "release" -> "Release this reference only. The private download remains in Transfers, where removal requires another explicit action and is blocked while other references or uncertain exports exist."
            "unpin" -> "Remove the pin and its references, not the downloaded bytes. Stop active candidates first. Existing copies remain in Transfers for review or inert export."
            else -> "Remove only this unreferenced sealed metadata cache. No remote path, private byte copy, owner index or external document is changed."
        },
        editName = false, consent = "I understand and want to continue.", button = "I reviewed · continue", enabled = !state.busy, cancel = { review = null },
    ) {
        when (r.kind) {
            "pin" -> r.id?.let { actions.pin(it, true) }
            "refresh" -> r.id?.let { actions.refreshPin(it, true) }
            "adopt" -> r.id?.let { actions.adopt(it, true) }
            "release" -> r.id?.let { id -> r.download?.let { actions.release(id, it, true) } }
            "unpin" -> r.id?.let { actions.unpin(it, true) }
            else -> actions.forget(true)
        }; review = null
    }
}
fun offlineMessage(code: String): String = when (code) {
    "SYNC_ACCESS_DENIED", "SYNC_SCOPE_CHANGED", "SYNC_STOPPED" -> "The saved root or authority changed. Reload the private view and refresh with current Read + SyncRead grants. Existing verified copies are not erased."
    "CURSOR_EXPIRED" -> "The cursor expired or its root/actor/lease changed. A fresh stable snapshot is required; no stale cursor is treated as current."
    "SYNC_INCOMPLETE", "SYNC_PAGE_LIMIT", "SYNC_INTERRUPTED" -> "The metadata refresh is incomplete. The previous completed view and byte copies are retained. Continue explicitly; no partial view means availability."
    "SYNC_LOCAL_LIMIT", "PIN_LIMIT", "SYNC_SNAPSHOT_LIMIT" -> "The bounded metadata or pin quota is full. Nothing was silently evicted. Review unused metadata or pins before adding another."
    "PIN_CANDIDATE_EXISTS" -> "A separate candidate already exists. Review, resume, adopt or release it before creating another."
    "PIN_PREVIOUS_RETAINED", "PIN_REFERENCED" -> "A retained reference protects this copy. Explicitly release the old reference or unpin first; downloaded bytes remain until separately removed."
    "PIN_SOURCE_DELETED" -> "The source object is gone. Your saved copy is retained. A file at the same path is not assumed to be the same object."
    "PIN_ALREADY_CURRENT" -> "The observed source version is already pinned. No redundant replacement was queued."
    "SYNC_REMOTE_STORAGE" -> "The desktop could not read its scoped metadata. No phone keys or retained byte copies were reset."
    else -> transferMessage(code)
}
