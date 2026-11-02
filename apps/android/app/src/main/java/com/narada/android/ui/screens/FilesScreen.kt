package com.narada.android.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.narada.android.R
import com.narada.android.connection.ConnectionsUiState
import com.narada.android.files.fileMessage
import com.narada.android.ui.*
import com.narada.android.offline.OfflineUiState
import com.narada.connection.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FileActions(
    val visible: (Boolean) -> Unit = {}, val roots: () -> Unit = {}, val refresh: () -> Unit = {}, val back: () -> Unit = {},
    val openRoot: (String) -> Unit = {}, val directory: (String) -> Unit = {}, val breadcrumb: (String?) -> Unit = {},
    val search: (String) -> Unit = {}, val sort: (FileSort) -> Unit = {}, val trash: () -> Unit = {},
    val next: () -> Unit = {}, val previous: () -> Unit = {}, val select: (String) -> Unit = {},
    val preview: () -> Unit = {}, val close: () -> Unit = {},
    val downloadAvailable: Boolean = false, val download: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val mutate: (FileAction, String, String, Boolean) -> Unit = { _, _, _, _ -> },
    val retry: (String, Boolean) -> Unit = { _, _ -> }, val dismiss: (String, Boolean) -> Unit = { _, _ -> },
)

@Composable
fun FilesScreen(state: FileBrowserState, connections: ConnectionsUiState, actions: FileActions, devices: () -> Unit) = FilesScreen(state, connections, actions, OfflineUiState(), OfflineActions(), devices)
@Composable
fun FilesScreen(state: FileBrowserState, connections: ConnectionsUiState, actions: FileActions, offline: OfflineUiState, offlineActions: OfflineActions, devices: () -> Unit) {
    // No file names, queries, selections, pixels, confirmation drafts or cursor stacks in saved instance state.
    CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
        var saved by remember { mutableStateOf(false) }
        DisposableEffect(saved) { actions.visible(!saved); onDispose { actions.visible(false) } }
        BackHandler(enabled = !saved && (state.location != null || state.selection != null)) { actions.back() }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ saved = false }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Live files") }
                OutlinedButton({ saved = true }, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Offline pins") }
            }
            Box(Modifier.weight(1f)) { if (saved) OfflineScreen(offline, connections, offlineActions) else FilesContent(state, connections, actions, devices) }
        }
    }
}

@Composable
private fun FilesContent(state: FileBrowserState, connections: ConnectionsUiState, actions: FileActions, devices: () -> Unit) {
    val location = state.location; val page = state.page; val trashPage = state.trashPage
    val error = state.error; val notice = state.notice
    val peer = connections.document?.peers?.find { it.id == connections.document.preferredPeer }
    var query by remember(location?.rootId, location?.search) { mutableStateOf(location?.search.orEmpty()) }
    var sortOpen by remember { mutableStateOf(false) }
    var recovery by remember { mutableStateOf<Pair<FileOperation, Boolean>?>(null) }
    val unknown = state.operations.filter { it.state == FileOperationState.UNCONFIRMED }
    val enabled = state.authorized && !state.busy
    LazyColumn(Modifier.fillMaxSize().testTag("remote_files").imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Eyebrow("AUTHORIZED DESKTOP FILES")
                Text(peer?.name ?: "Your shared workspace", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Caption("Only roots currently granted by your desktop. Browsing keeps no offline listing. A download is a separate, explicit verified-copy action.")
                OutlinedButton(devices, Modifier.heightIn(min = 48.dp)) { Glyph(R.drawable.ic_devices); Spacer(Modifier.width(8.dp)); Text("Devices & permissions") }
            }
        }
        if (!state.authorized) item {
            InfoPanel("Not ready to browse", "Connect an approved desktop on private Wi-Fi or Ethernet. This phone uses fresh authenticated permission checks, not saved grants alone.")
        }
        if (error != null) item {
            Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("file_error")) { InfoPanel("Request needs attention", fileMessage(error)) }
        }
        if (notice != null) item { Caption(notice, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.busy) item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Text("Checking current access…")
        } }
        if (unknown.isNotEmpty()) item { Text("Unconfirmed operations · ${unknown.size}", style = MaterialTheme.typography.titleMedium) }
        items(unknown, key = { "operation-${it.request.operationId}" }) { operation ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${operation.request.action.kind.replaceFirstChar { it.uppercase() }} · outcome unknown", fontWeight = FontWeight.SemiBold)
                Caption("Saved operation ${operation.request.operationId}. The desktop may already have applied it. No automatic retry or replacement request.")
                operation.request.action.name?.let { Caption("Requested name: $it") }
                OutlinedButton({ recovery = operation to true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled && operation.peerId == peer?.id && operation.pairingAttempt == peer.attemptId) { Text("Retry the same operation") }
                TextButton({ recovery = operation to false }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy) { Text("Review / dismiss local retry") }
            } }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (location != null) OutlinedButton(actions.roots, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled) { Text("All roots") }
                Button(actions.refresh, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled) { Text(if (location == null) "Refresh roots" else "Refresh view") }
            }
        }
        if (location == null) {
            items(state.roots, key = { it.root_id }) { root ->
                OutlinedCard(onClick = { actions.openRoot(root.root_id) }, modifier = Modifier.fillMaxWidth(), enabled = enabled) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        TileIcon(R.drawable.ic_folder)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(root.label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Caption("${root.entry_count} indexed · ${root.state.replace('_', ' ')}")
                            Caption(root.capabilities.joinToString(" · ") { it.replace('_', ' ') })
                        }
                        Glyph(R.drawable.ic_right)
                    }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.root?.label ?: "Granted root", style = MaterialTheme.typography.titleLarge)
                    if (location.trash) Caption("Managed trash · no permanent delete or automatic purge")
                    else {
                        page?.breadcrumbs?.takeIf { it.size > 1 }?.let { crumbs ->
                            Column {
                                crumbs.forEachIndexed { index, crumb -> TextButton({ actions.breadcrumb(crumb.object_id) }, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text(if (index == 0) crumb.name else "› ${crumb.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                            }
                        }
                        OutlinedTextField(query, { if (it.toByteArray(Charsets.UTF_8).size <= 128) query = it }, Modifier.fillMaxWidth().testTag("file_search"), label = { Text("Search this root") }, supportingText = { Text("Name search · 128 UTF-8 bytes · tap Search") }, singleLine = true, enabled = !state.busy)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button({ actions.search(query) }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled) { Text("Search") }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton({ sortOpen = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = enabled) { Text(location.sort.label) }
                                DropdownMenu(sortOpen, { sortOpen = false }) { FileSort.entries.forEach { sort -> DropdownMenuItem(text = { Text(sort.label) }, onClick = { sortOpen = false; actions.sort(sort) }) } }
                            }
                        }
                        if ("trash" in state.root?.capabilities.orEmpty()) TextButton(actions.trash, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text("Open managed trash") }
                    }
                    if (location.directoryId != null || location.search != null || location.trash) OutlinedButton(actions.back, Modifier.heightIn(min = 48.dp), enabled = enabled) { Text("Back to parent / root") }
                }
            }
            if (page != null) {
                if (page.entries.isEmpty()) item { InfoPanel("No live entries on this page", "The folder/search may be empty, or changed entries were omitted during live checks. This is not a cached offline listing.") }
                items(page.entries, key = { it.object_id }) { entry ->
                    FileRow(entry.name, if (entry.kind == "directory") "Directory · open to browse" else "File · ${fileSize(entry.size)}", enabled, directory = entry.kind == "directory",
                        open = { if (entry.kind == "directory") actions.directory(entry.object_id) else actions.select(entry.object_id) }, details = { actions.select(entry.object_id) })
                }
            }
            if (trashPage != null) {
                if (trashPage.entries.isEmpty()) item { InfoPanel("No available retained objects", "Unavailable or changed retained objects keep their desktop records and quota for owner recovery.") }
                if (trashPage.unavailable_count > 0) item { Caption("${trashPage.unavailable_count} unavailable retained objects on this page. Review them on the desktop.") }
                items(trashPage.entries, key = { it.object_id }) { entry -> FileRow(entry.name, "Retained · ${fileSize(entry.size)}", enabled, entry.kind == "directory", { actions.select(entry.object_id) }, { actions.select(entry.object_id) }) }
            }
            if (page != null || trashPage != null) item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Caption("Page ${state.pageNumber} · up to 16 live entries. Cursors expire after 60 seconds; refresh restarts the view.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(actions.previous, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled && state.canPrevious) { Text("Previous") }
                        OutlinedButton(actions.next, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled && state.nextCursor != null) { Text("Next page") }
                    }
                }
            }
        }
        item { HorizontalDivider(); Spacer(Modifier.height(12.dp)); Caption("Only granted folders are available. Export downloaded copies from Transfers.") }
    }
    state.selection?.let { selected -> SelectedFileDialog(state, selected, actions, unknown.any { it.peerId == peer?.id }) }
    recovery?.let { (operation, retry) ->
        var accepted by remember(operation.request.operationId, retry) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { recovery = null }, title = { Text(if (retry) "Retry the exact request?" else "Dismiss only local retry?") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (retry) "This resends the same operation identifier and unchanged arguments. If the desktop already committed it, it returns the receipt. If it never received it, this may apply the change now. Current grants are checked again."
                    else "Inspect the desktop first. Dismissal does not undo, cancel, confirm or delete the desktop operation. Its outcome remains unknown in local history; it will not be automatically retried.")
                ConsentRow(accepted, { accepted = it }, "I understand and want to continue.")
            } },
            confirmButton = { TextButton({ recovery = null; if (retry) actions.retry(operation.request.operationId, accepted) else actions.dismiss(operation.request.operationId, accepted) }, enabled = accepted && !state.busy && (!retry || state.authorized)) { Text(if (retry) "Retry exact request" else "Dismiss local retry") } },
            dismissButton = { TextButton({ recovery = null }) { Text("Keep unchanged") } })
    }
}

@Composable
private fun FileRow(name: String, detail: String, enabled: Boolean, directory: Boolean, open: () -> Unit, details: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 80.dp).clickable(enabled = enabled, onClickLabel = "Open $name", onClick = open).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Glyph(if (directory) R.drawable.ic_folder else R.drawable.ic_file)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis); Caption(detail) }
            }
            IconButton(details, Modifier.size(48.dp), enabled = enabled) { Glyph(R.drawable.ic_more, description = "Details and permitted actions for $name") }
        }
    }
}

@Composable
private fun SelectedFileDialog(state: FileBrowserState, selected: FileSelection, actions: FileActions, unresolved: Boolean) {
    var action by remember(selected.objectId, selected.version) { mutableStateOf<String?>(null) }
    val caps = state.root?.capabilities.orEmpty()
    val editable = state.authorized && !state.busy && !unresolved
    val kind = action
    if (kind == "download") {
        TransferConsentDialog("Save a verified private copy?", selected.name,
            "Download this exact ordinary file, up to 64 MiB. NARADA checks the immutable manifest, each chunk and the complete file, then saves an encrypted private receipt/copy. Keep the app visible. Nothing is opened or exported automatically; choose a separate SAF export later in Transfers.",
            false, "I want to download this exact file privately.", "Queue download", state.authorized && !state.busy && actions.downloadAvailable, { action = null }) {
            actions.download(selected.objectId, selected.version, true)
        }
        return
    }
    if (kind != null) {
        FileMutationConfirmation(selected, kind, editable, { action = null }) { value ->
            actions.mutate(value, selected.objectId, selected.version, true)
        }
        return
    }
    AlertDialog(onDismissRequest = actions.close, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn), title = { Text(selected.name, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption("${selected.kind.replaceFirstChar { it.uppercase() }} · ${fileSize(selected.entry?.size ?: selected.retained!!.size)}")
            Caption("Object ${selected.objectId}")
            selected.entry?.let { Caption("Modified ${it.modified}") }
            selected.retained?.let { Caption("Retained until at least ${it.retain_until}. No automatic purge or permanent delete.") }
            state.preview?.let { BoundedImage(it) }
            if (selected.entry?.preview_candidate == true) OutlinedButton(actions.preview, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = editable && "read" in caps) { Text("Show bounded image preview") }
            selected.entry?.let { entry -> if (entry.kind == "file") OutlinedButton({ action = "download" }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = state.authorized && !state.busy && actions.downloadAvailable && "read" in caps && runCatching { TransferRules.size(entry.size) }.isSuccess) { Text("Save verified private copy") } }
            if (selected.retained == null) {
                OutlinedButton({ action = "rename" }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = editable && "modify" in caps) { Text("Rename on desktop") }
                OutlinedButton({ action = "trash" }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = editable && "trash" in caps) { Text("Move to managed trash") }
            } else OutlinedButton({ action = "restore" }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = editable && "trash" in caps && "create" in caps) { Text("Restore on desktop") }
            Caption("Actions require current desktop grants. Only files and empty directories can be changed. No overwrite or recursive delete.")
            if (unresolved) Caption("Review this device’s unconfirmed operation before starting another mutation.")
            Caption("Preview is a re-encoded image of at most 256 × 256 pixels / 32 KiB. It is not the original file, a download or an offline copy.")
        } }, confirmButton = { TextButton(actions.close) { Text("Close details") } })
}

/** A separate, bounded full-screen confirmation avoids intrinsic-size remeasure loops when a
 * text field replaces the detail dialog, and leaves scrolling space for 200% type and the IME. */
@Composable
private fun FileMutationConfirmation(selected: FileSelection, kind: String, editable: Boolean, cancel: () -> Unit, confirm: (FileAction) -> Unit) {
    var name by remember(selected.objectId, selected.version, kind) { mutableStateOf(selected.name) }
    var accepted by remember(selected.objectId, selected.version, kind) { mutableStateOf(false) }
    val nameValid = kind == "trash" || runCatching { FileRules.newName(name) }.isSuccess && (kind != "rename" || name != selected.name)
    Dialog(onDismissRequest = cancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Eyebrow("EXPLICIT DESKTOP CHANGE")
                    Text(when (kind) { "rename" -> "Rename on desktop"; "restore" -> "Restore on desktop"; else -> "Move to managed trash" }, style = MaterialTheme.typography.headlineSmall)
                    Text(selected.name, style = MaterialTheme.typography.titleMedium)
                    Caption("Selected object ${selected.objectId}")
                    if (kind != "trash") OutlinedTextField(name, { if (it.toByteArray(Charsets.UTF_8).size <= 180) name = it }, Modifier.fillMaxWidth(), label = { Text(if (kind == "rename") "New name" else "Restore name") }, singleLine = true, isError = !nameValid, supportingText = { Text("No separators, hidden/reserved names, overwrite or trailing dot/space.") })
                    Caption(if (kind == "restore") "Restores to the original parent using this name. Current trash and create grants are both required." else "This changes the desktop object, not a local phone copy. Only files and empty directories are supported.")
                    Caption("The phone saves the exact operation before sending it. A timeout or cancellation can leave an unknown outcome; it cannot undo a request already processed by the desktop.")
                    ConsentRow(accepted, { accepted = it }, "I confirm this change on the desktop.")
                }
                Button({ confirm(FileAction(kind, if (kind == "trash") null else name)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = editable && accepted && nameValid) { Text("Confirm $kind") }
                OutlinedButton(cancel, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Keep unchanged") }
            }
        }
    }
}

@Composable
private fun ConsentRow(checked: Boolean, change: (Boolean) -> Unit, label: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { change(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, change); Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Two native bounds checks, no ImageLoader/network cache/URI/HTML/external viewer. */
fun decodeBoundedPreview(preview: PixelPreview): Bitmap {
    val bytes = preview.encodedCopy()
    try {
        JpegBounds.validate(bytes, preview.width, preview.height)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        demand(bounds.outMimeType == "image/jpeg" && bounds.outWidth == preview.width && bounds.outHeight == preview.height, "INVALID_PREVIEW")
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
        val result = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: fail("INVALID_PREVIEW")
        if (result.width != preview.width || result.height != preview.height || result.allocationByteCount > 256 * 256 * 4) { result.recycle(); fail("INVALID_PREVIEW") }
        return result
    } finally { bytes.fill(0) }
}
@Composable
private fun BoundedImage(preview: PixelPreview) {
    val decoded by produceState<Result<Bitmap>?>(null, preview) { value = withContext(Dispatchers.Default) { runCatching { decodeBoundedPreview(preview) } } }
    val bitmap = decoded?.getOrNull()
    if (bitmap != null) Image(bitmap.asImageBitmap(), contentDescription = "Bounded pixel preview of the selected image", modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 256.dp), contentScale = ContentScale.Fit)
    else Caption(if (decoded == null) "Decoding bounded pixels…" else "The phone decoder refused this preview. No external app was opened.")
    Caption("${preview.width} × ${preview.height} pixels · original metadata stripped")
}
private fun fileSize(value: String): String {
    val bytes = value.toULongOrNull() ?: return "Unknown size"
    return when { bytes >= 1_048_576u -> "${bytes / 1_048_576u} MiB"; bytes >= 1024u -> "${bytes / 1024u} KiB"; else -> "$bytes bytes" }
}
