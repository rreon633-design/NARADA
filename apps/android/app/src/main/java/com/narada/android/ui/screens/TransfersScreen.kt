package com.narada.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.narada.android.R
import com.narada.android.transfers.TransfersUiState
import com.narada.android.transfers.transferMessage
import com.narada.android.ui.*
import com.narada.connection.*

data class TransferActions(
    val visible: (Boolean) -> Unit = {}, val reload: () -> Unit = {}, val pause: (String) -> Unit = {}, val pauseAll: () -> Unit = {},
    val resume: (String, Boolean) -> Unit = { _, _ -> }, val cancel: (String, Boolean) -> Unit = { _, _ -> },
    val remove: (String, Boolean) -> Unit = { _, _ -> }, val finish: (String) -> Unit = {},
    val export: (String, String, Boolean) -> Unit = { _, _, _ -> }, val stopExport: () -> Unit = {},
    val dismissExport: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val forgetExport: (String, String, Boolean) -> Unit = { _, _, _ -> },
)
private data class TransferReviewChoice(val record: DownloadRecord, val kind: String, val exportId: String? = null)
@Composable
fun TransfersScreen(state: TransfersUiState, actions: TransferActions, files: () -> Unit) {
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        DisposableEffect(Unit) { actions.visible(true); onDispose { actions.visible(false) } }
        TransfersContent(state, actions, files)
    }
}
@Composable
private fun TransfersContent(state: TransfersUiState, actions: TransferActions, files: () -> Unit) {
    var filter by remember { mutableStateOf("All") }
    var review by remember { mutableStateOf<TransferReviewChoice?>(null) }
    val blocked = state.providerBusy || state.picker != null
    val rows = state.records.filter { when (filter) {
        "Active" -> it.state in setOf(DownloadState.QUEUED, DownloadState.RUNNING, DownloadState.PAUSED)
        "Saved" -> it.state == DownloadState.VERIFIED
        "Review" -> it.error != null || it.state in setOf(DownloadState.CANCELLED, DownloadState.CORRUPT) || it.exports.any { e -> e.state == ExportState.UNCERTAIN }
        else -> true
    } }
    LazyColumn(Modifier.fillMaxSize().testTag("verified_transfers").imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Eyebrow("VERIFIED COPIES")
            Text("Your files.\nA checked copy.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Caption("Download an ordinary desktop file, verify it privately, then choose whether to export. No automatic publication, file opening or background presence.")
            OutlinedButton(files, Modifier.heightIn(min = 48.dp)) { Glyph(R.drawable.ic_folder); Spacer(Modifier.width(8.dp)); Text("Choose from Files") }
        } }
        item { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "Active", "Saved", "Review").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp)) }
        } }
        item { InfoPanel("${state.records.count { it.state == DownloadState.RUNNING }} running · ${state.records.count { it.state == DownloadState.QUEUED }} queued", "Keep NARADA visible. Leaving the app or losing the route pauses downloads; resume is explicit. Up to four retained jobs / 128 MiB; 64 MiB per file.") }
        if (state.loading) item { Text("Opening protected transfer records…") }
        state.error?.let { code -> item { InfoPanel("Needs attention", transferMessage(code)) } }
        if (state.providerBusy) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoPanel("Export provider is working", "Only one provider operation is admitted. A timed-out provider may still finish admitted IO; its worker is not replaced. The private copy stays separate.")
            OutlinedButton(actions.stopExport, Modifier.heightIn(min = 48.dp)) { Text("Stop export") }
        } }
        if (state.picker != null) item { Caption("Waiting for your explicit folder choice. No provider document has been written by this phone yet.") }
        if (rows.isEmpty() && !state.loading) item { InfoPanel(if (state.records.isEmpty()) "No downloads yet" else "No records in this view", "Select a permitted ordinary file in Files, then choose Save verified private copy. Previews are not downloads.") }
        items(rows, key = { it.id }) { r ->
            OutlinedCard(Modifier.fillMaxWidth().testTag("transfer_${r.id}")) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Glyph(R.drawable.ic_file)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Caption(downloadStateLabel(r.state)); Caption("${transferSize(r.verifiedBytes)} checked / ${transferSize(r.size)}")
                    }
                }
                val progress = if (r.size == 0L) { if (r.state == DownloadState.VERIFIED) 1f else 0f } else (r.verifiedBytes.toDouble() / r.size).toFloat().coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                if (r.verifiedBytes == r.size && r.state != DownloadState.VERIFIED) Caption("All staged bytes still require full-file verification and a durable receipt. This is not a completed copy.")
                r.error?.let { Caption(transferMessage(it)) }
                Caption("Transfer ${r.id}")
                when (r.state) {
                    DownloadState.QUEUED, DownloadState.RUNNING -> OutlinedButton({ actions.pause(r.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Pause transfer") }
                    DownloadState.PAUSED -> OutlinedButton({ actions.resume(r.id, true) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !blocked && state.foreground && (state.networkEligible || r.stagedComplete)) { Text(if (r.stagedComplete) "Verify saved fragments" else "Resume exact transfer") }
                    DownloadState.VERIFIED -> {
                        Caption("Private receipt saved. Every export rechecks the full private copy; no preview or file viewer is launched.")
                        r.receipt?.let { Caption("SHA-256 · ${it.sha256}") }
                        Button({ review = TransferReviewChoice(r, "export") }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !blocked && r.exports.none { it.state in setOf(ExportState.PICKER_PENDING, ExportState.INTENT, ExportState.CREATED, ExportState.UNCERTAIN) }) { Text("Export a new copy…") }
                        if (!r.remoteFinished) {
                            Caption("Desktop finish acknowledgment is pending. This does not invalidate the saved private copy.")
                            TextButton({ actions.finish(r.id) }, Modifier.heightIn(min = 48.dp), enabled = state.networkEligible && !blocked) { Text("Acknowledge to desktop") }
                        }
                    }
                    else -> Unit
                }
                r.exports.forEach { e ->
                    Text(when (e.state) {
                        ExportState.VERIFIED -> "Export readback verified · ${e.name}"
                        ExportState.NOT_CREATED -> "No new document created by this attempt"
                        ExportState.DISMISSED_UNCONFIRMED -> "Export outcome unconfirmed · local review dismissed"
                        ExportState.UNCERTAIN -> "Export outcome unconfirmed"
                        else -> "Export intent saved · not complete"
                    }, style = MaterialTheme.typography.labelLarge)
                    if (e.state in setOf(ExportState.VERIFIED, ExportState.NOT_CREATED, ExportState.DISMISSED_UNCONFIRMED)) TextButton({ review = TransferReviewChoice(r, "forget", e.id) }, Modifier.heightIn(min = 48.dp), enabled = !blocked) { Text("Remove this local export record") }
                    if (e.state == ExportState.UNCERTAIN) {
                        Caption("Inspect the chosen provider. A new empty, partial or full document may exist. Nothing is retried or deleted automatically.")
                        OutlinedButton({ review = TransferReviewChoice(r, "dismiss", e.id) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !blocked) { Text("Review export uncertainty") }
                    }
                }
                if (r.state in setOf(DownloadState.QUEUED, DownloadState.RUNNING, DownloadState.PAUSED)) TextButton({ review = TransferReviewChoice(r, "cancel") }, Modifier.heightIn(min = 48.dp), enabled = !blocked) { Text("Cancel on this phone") }
                if (r.state != DownloadState.RUNNING) TextButton({ review = TransferReviewChoice(r, "remove") }, Modifier.heightIn(min = 48.dp), enabled = !blocked && r.exports.none { it.state in setOf(ExportState.PICKER_PENDING, ExportState.INTENT, ExportState.CREATED, ExportState.UNCERTAIN) }) { Text("Remove private data & record") }
            } }
        }
        item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(actions.pauseAll, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = state.records.any { it.state in setOf(DownloadState.QUEUED, DownloadState.RUNNING) }) { Text("Pause all downloads") }
            TextButton(actions.reload, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !blocked) { Text("Reopen protected records") }
            Caption("Ordinary desktop-to-phone downloads and offline candidates. Encrypted attachments use Chat. No automatic filesystem sync. Cancel is local; already admitted work and provider copies cannot be undone.")
        } }
    }
    review?.let { choice ->
        val current = state.records.find { it.id == choice.record.id }
        if (current == null) { LaunchedEffect(Unit) { review = null } } else {
            val title = when (choice.kind) { "export" -> "Export outside NARADA?"; "cancel" -> "Cancel only on this phone?"; "dismiss" -> "Dismiss only local review?"; "forget" -> "Remove only the local export record?"; else -> "Remove the private copy?" }
            val explanation = when (choice.kind) {
                "export" -> "Choose a folder after confirming. NARADA creates a new binary document, never replaces an existing one, and reads it back before reporting success. Your provider may upload, share or retain its copy. An interruption may leave an empty, partial or full new document. The verified private copy remains separate."
                "cancel" -> "This stops further phone work and retains its staging for your review. It does not delete the desktop original, undo an admitted request or retract an exported copy."
                "forget" -> "This permanently removes only the reviewed local export record, not the verified private copy. It does not verify, delete or recall any provider document. An unconfirmed export remains unconfirmed; save any details you need first."
                "dismiss" -> "Inspect the provider first. Dismissal does not prove failure, delete the document or undo the export. A later export is a separately confirmed new copy, never an automatic rewrite."
                else -> "This deletes this job’s private encrypted fragments and local record. If you have no verified export, this may be your only received copy. Desktop originals and external provider documents are never deleted."
            }
            val destination = current.exports.find { it.id == choice.exportId }
            val details = if (destination != null) "\n\nProvider tree URI: ${destination.tree ?: "No destination recorded"}\nDocument URI: ${destination.document ?: "No document URI received"}" else ""
            TransferConsentDialog(title, current.name, explanation + details, choice.kind == "export", "I understand and want to continue.", when (choice.kind) { "export" -> "Choose export folder"; "cancel" -> "Confirm local cancel"; "dismiss" -> "Dismiss local review"; "forget" -> "Remove local export record"; else -> "Confirm removal" }, !blocked, { review = null }) { name ->
                review = null
                when (choice.kind) { "export" -> actions.export(current.id, name, true); "cancel" -> actions.cancel(current.id, true); "dismiss" -> actions.dismissExport(current.id, choice.exportId!!, true); "forget" -> actions.forgetExport(current.id, choice.exportId!!, true); else -> actions.remove(current.id, true) }
            }
        }
    }
}
@Composable
fun TransferConsentDialog(title: String, originalName: String, body: String, editName: Boolean, consent: String, button: String, enabled: Boolean, cancel: () -> Unit, confirm: (String) -> Unit) {
    var name by remember(originalName) { mutableStateOf(originalName) }
    var accepted by remember { mutableStateOf(false) }
    val valid = !editName || runCatching { FileRules.newName(name) }.isSuccess
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Eyebrow("EXPLICIT FILE CONSENT"); Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(originalName, style = MaterialTheme.typography.titleMedium); Text(body)
                if (editName) OutlinedTextField(name, { if (it.toByteArray().size <= 180) name = it }, Modifier.fillMaxWidth(), label = { Text("New export name") }, singleLine = true, isError = !valid)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { accepted = !accepted }, verticalAlignment = Alignment.CenterVertically) { Checkbox(accepted, { accepted = it }); Text(consent, Modifier.weight(1f)) }
            }
            Button({ confirm(name) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && accepted && valid) { Text(button) }
            OutlinedButton(cancel, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Keep unchanged") }
        } }
    }
}
fun downloadStateLabel(state: DownloadState): String = when (state) {
    DownloadState.QUEUED -> "Queued · not yet received"
    DownloadState.RUNNING -> "Receiving / verifying · not complete"
    DownloadState.PAUSED -> "Paused · explicit resume required"
    DownloadState.VERIFIED -> "Verified private receipt"
    DownloadState.CANCELLED -> "Cancelled locally · not running"
    DownloadState.CORRUPT -> "Private copy needs recovery"
}
fun transferSize(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "${bytes / 1024} KiB"; else -> String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0) }
