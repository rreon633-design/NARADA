package com.narada.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A value fingerprint of authority, not of transient 'health is checking' presentation. */
data class FileAuthority(val peerId: String, val attemptId: String, val localEpoch: Long, val remoteRevision: Long, val remoteEpoch: Long, val policyEpoch: Long)
data class FileLocation(val rootId: String, val directoryId: String? = null, val search: String? = null, val trash: Boolean = false, val sort: FileSort = FileSort.NAME) {
    fun query(cursor: String? = null): FileQuery = when { trash -> FileQuery.Trash(rootId, cursor); search != null -> FileQuery.Search(rootId, search, cursor, sort); else -> FileQuery.Browse(rootId, directoryId, cursor, sort) }
}
data class FileSelection(val rootId: String, val entry: RemoteEntry? = null, val retained: TrashEntry? = null) {
    val objectId get() = entry?.object_id ?: retained!!.object_id
    val version get() = entry?.version ?: retained!!.version
    val name get() = entry?.name ?: retained!!.name
    val kind get() = entry?.kind ?: retained!!.kind
}
data class FileBrowserState(
    val visible: Boolean = false, val authorized: Boolean = false, val busy: Boolean = false,
    val roots: List<RemoteRoot> = emptyList(), val location: FileLocation? = null,
    val page: PageReply? = null, val trashPage: TrashReply? = null, val pageNumber: Int = 1, val canPrevious: Boolean = false,
    val selection: FileSelection? = null, val preview: PixelPreview? = null,
    val operations: List<FileOperation> = emptyList(), val error: String? = null, val notice: String? = null,
) {
    val nextCursor get() = page?.next_cursor ?: trashPage?.next_cursor
    val root get() = page?.root ?: trashPage?.root ?: roots.find { it.root_id == location?.rootId }
    val hasSensitiveContent get() = roots.isNotEmpty() || page != null || trashPage != null || selection != null || operations.isNotEmpty()
}

/** Single request, 16 current rows, at most 32 previous-page cursors, one <=32KiB preview. Memory only. */
class FileBrowser(private val gateway: FilesGateway, private val scope: CoroutineScope, private val metadataLifetimeMillis: Long = 60_000) {
    private val mutable = MutableStateFlow(FileBrowserState())
    val state = mutable.asStateFlow()
    private var authority: FileAuthority? = null
    private var job: Job? = null
    private var expiry: Job? = null
    private var generation = 0L
    private var contentGeneration = 0L
    private var cursors = listOf<String?>(null)
    private var savedOperations = emptyList<FileOperation>()
    fun authorityChanged(value: FileAuthority?, operations: List<FileOperation>, foreground: Boolean = true) {
        savedOperations = if (foreground) operations.takeLast(32) else emptyList()
        val changed = authority != value; authority = value
        if (changed || !foreground) {
            val visible = mutable.value.visible
            cancel(); cursors = listOf(null)
            mutable.value = FileBrowserState(visible = visible, authorized = value != null && foreground, operations = if (visible && foreground) savedOperations else emptyList(), notice = "Access changed. Refresh to fetch current, permitted content.")
        } else mutable.update { it.copy(authorized = value != null && foreground, operations = if (it.visible && foreground) savedOperations else emptyList()) }
    }
    fun visible(value: Boolean) {
        if (mutable.value.visible == value) return
        cancel(); cursors = listOf(null)
        mutable.value = FileBrowserState(visible = value, authorized = authority != null, operations = if (value) savedOperations else emptyList())
        if (value && authority != null) roots()
    }
    fun downloadChoice(objectId: String, version: String): DownloadChoice {
        val current = mutable.value; val captured = authority ?: fail("FILES_OFFLINE")
        val selected = current.selection ?: fail("FILE_SELECTION_STALE")
        demand(current.visible && current.authorized && !current.busy && selected.objectId == objectId && selected.version == version, "FILE_SELECTION_STALE")
        val entry = selected.entry ?: fail("TRANSFER_UNSUPPORTED")
        demand(entry.kind == "file" && current.root?.capabilities?.containsAll(listOf("browse", "read")) == true, "ACCESS_DENIED")
        return DownloadChoice(captured.peerId, captured.attemptId, TransferSource(selected.rootId, entry.object_id, entry.version), entry.name, TransferRules.size(entry.size))
    }
    fun cancel() { generation++; contentGeneration++; job?.cancel(); job = null; expiry?.cancel(); expiry = null; gateway.cancel() }
    private fun error(code: String) { mutable.update { it.copy(error = code) } }
    private fun request(allowOffline: Boolean = false, work: suspend () -> Unit) {
        if (!mutable.value.visible || (!allowOffline && authority == null)) { error("FILES_OFFLINE"); return }
        cancel(); val turn = generation; val capture = authority
        mutable.update { it.copy(busy = true, error = null, notice = null, preview = null, selection = null) }
        job = scope.launch {
            try {
                work()
                currentCoroutineContext().ensureActive()
                if (turn == generation && capture == authority) {
                    mutable.update { it.copy(busy = false) }
                    val contentTurn = contentGeneration
                    expiry = scope.launch {
                        delay(metadataLifetimeMillis)
                        if (contentTurn == contentGeneration) {
                            cancel(); cursors = listOf(null)
                            mutable.value = FileBrowserState(visible = true, authorized = authority != null, operations = savedOperations, notice = "The in-memory view expired. Refresh for current permissions and content.")
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (turn == generation && capture == authority) {
                    cursors = listOf(null)
                    mutable.update { it.copy(busy = false, roots = emptyList(), page = null, trashPage = null, preview = null, selection = null, canPrevious = false, pageNumber = 1,
                        error = (failure as? ConnectionFailure)?.code ?: "FILE_UNAVAILABLE") }
                }
            }
        }
    }
    /** Gate after every gateway suspension, even if a test/provider ignores coroutine cancellation. */
    private suspend fun current() { currentCoroutineContext().ensureActive() }
    fun roots() {
        mutable.update { it.copy(roots = emptyList(), location = null, page = null, trashPage = null) }; cursors = listOf(null)
        request {
            val reply = gateway.query(FileQuery.Roots) as? FileResult.Roots ?: fail("INVALID_FILE_RESPONSE"); current()
            mutable.update { it.copy(roots = reply.value.roots, notice = if (reply.value.roots.isEmpty()) "No browsable roots are currently granted by this desktop." else null) }
        }
    }
    fun refresh() { val location = mutable.value.location; if (location == null) roots() else load(location) }
    fun openRoot(id: String) {
        if (mutable.value.roots.none { it.root_id == id }) { error("ACCESS_DENIED"); return }
        load(FileLocation(id))
    }
    fun openDirectory(id: String) {
        val state = mutable.value; val location = state.location ?: return
        if (state.page?.entries?.none { it.object_id == id && it.kind == "directory" } != false) { error("FILE_SELECTION_STALE"); return }
        load(FileLocation(location.rootId, directoryId = id, sort = location.sort))
    }
    fun breadcrumb(id: String?) {
        val state = mutable.value; val location = state.location ?: return
        if (state.page?.breadcrumbs?.none { it.object_id == id } != false) { error("FILE_SELECTION_STALE"); return }
        load(FileLocation(location.rootId, directoryId = id, sort = location.sort))
    }
    fun back() {
        val state = mutable.value
        if (state.selection != null) { closeSelection(); return }
        val location = state.location ?: return
        val crumbs = state.page?.breadcrumbs.orEmpty()
        when {
            location.search != null || location.trash -> load(FileLocation(location.rootId, sort = location.sort))
            location.directoryId != null && crumbs.size > 1 -> load(FileLocation(location.rootId, directoryId = crumbs[crumbs.lastIndex - 1].object_id, sort = location.sort))
            else -> roots()
        }
    }
    fun search(query: String) {
        val location = mutable.value.location ?: return
        try { if (query.isNotBlank()) FileRules.search(query) } catch (failure: ConnectionFailure) { error(failure.code); return }
        load(FileLocation(location.rootId, search = query.takeIf { it.isNotBlank() }, sort = location.sort))
    }
    fun sort(sort: FileSort) { mutable.value.location?.let { if (!it.trash) load(it.copy(sort = sort)) } }
    fun trash() {
        val state = mutable.value; val location = state.location ?: return
        if ("trash" !in state.root?.capabilities.orEmpty()) { error("ACCESS_DENIED"); return }
        load(FileLocation(location.rootId, trash = true))
    }
    fun next() {
        val state = mutable.value; val cursor = state.nextCursor ?: return; val location = state.location ?: return
        if (cursors.lastOrNull() == cursor || cursor in cursors) { error("CURSOR_STALE"); return }
        load(location, cursor, (cursors + cursor).takeLast(33), state.pageNumber + 1, state.root?.index_epoch)
    }
    fun previous() {
        val state = mutable.value; val location = state.location ?: return
        if (cursors.size < 2) return
        val previous = cursors.dropLast(1)
        load(location, previous.last(), previous, state.pageNumber - 1, state.root?.index_epoch)
    }
    private fun load(location: FileLocation, cursor: String? = null, history: List<String?> = listOf(null), number: Int = 1, previousEpoch: String? = null) {
        mutable.update { it.copy(location = location, page = null, trashPage = null, pageNumber = number, canPrevious = false) }
        request {
            val reply = gateway.query(location.query(cursor)); current()
            val root = when (reply) { is FileResult.Page -> reply.value.root; is FileResult.Trash -> reply.value.root; else -> fail("INVALID_FILE_RESPONSE") }
            demand(root.root_id == location.rootId && (previousEpoch == null || previousEpoch == root.index_epoch), "CURSOR_STALE")
            val next = when (reply) { is FileResult.Page -> reply.value.next_cursor; is FileResult.Trash -> reply.value.next_cursor; else -> null }
            demand(next == null || next !in history, "CURSOR_STALE")
            cursors = history
            mutable.update { it.copy(roots = it.roots.map { old -> if (old.root_id == root.root_id) root else old }, page = (reply as? FileResult.Page)?.value,
                trashPage = (reply as? FileResult.Trash)?.value, canPrevious = cursors.size > 1) }
        }
    }
    fun select(objectId: String) {
        val state = mutable.value; val location = state.location ?: return
        val retained = state.trashPage?.entries?.find { it.object_id == objectId }
        if (retained != null) { mutable.update { it.copy(selection = FileSelection(location.rootId, retained = retained), preview = null) }; return }
        val entry = state.page?.entries?.find { it.object_id == objectId } ?: run { error("FILE_SELECTION_STALE"); return }
        request {
            val reply = gateway.query(FileQuery.Details(location.rootId, objectId)) as? FileResult.Details ?: fail("INVALID_FILE_RESPONSE"); current()
            demand(reply.value == entry, "STALE_VERSION")
            mutable.update { it.copy(selection = FileSelection(location.rootId, entry = reply.value)) }
        }
    }
    fun preview() {
        val state = mutable.value; val selected = state.selection ?: return; val entry = selected.entry ?: return
        if (!entry.preview_candidate || "read" !in state.root?.capabilities.orEmpty()) { error("PREVIEW_UNSUPPORTED"); return }
        request {
            val reply = gateway.query(FileQuery.Preview(selected.rootId, entry.object_id, entry.version)) as? FileResult.Preview ?: fail("INVALID_FILE_RESPONSE"); current()
            demand(reply.value.objectId == entry.object_id && reply.value.version == entry.version, "STALE_VERSION")
            mutable.update { it.copy(selection = selected, preview = reply.value) }
        }
    }
    fun closeSelection() {
        // Close the request/preview, but keep the original metadata expiry timer alive.
        generation++; job?.cancel(); job = null; gateway.cancel()
        mutable.update { it.copy(selection = null, preview = null, busy = false) }
    }
    fun mutate(action: FileAction, expectedId: String, expectedVersion: String, confirmed: Boolean) {
        val selected = mutable.value.selection ?: run { error("FILE_SELECTION_STALE"); return }
        if (selected.objectId != expectedId || selected.version != expectedVersion || (action.kind == "restore") != (selected.retained != null)) { error("FILE_SELECTION_STALE"); return }
        try { demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED"); action.validate(); demand(action.kind != "rename" || action.name != selected.name, "NAME_COLLISION") } catch (failure: ConnectionFailure) { error(failure.code); return }
        mutable.update { it.copy(page = null, trashPage = null) }
        request {
            val operation = gateway.mutate(selected.rootId, selected.objectId, selected.version, action, confirmed); current()
            demand(operation.state == FileOperationState.COMMITTED, "INVALID_FILE_RECEIPT")
            mutable.update { it.copy(notice = "Desktop committed ${action.kind}; the receipt was saved. Refresh for current files.") }
        }
    }
    fun retry(operationId: String, confirmed: Boolean) {
        mutable.update { it.copy(page = null, trashPage = null) }
        request {
            val operation = gateway.retry(operationId, confirmed); current(); demand(operation.state == FileOperationState.COMMITTED, "INVALID_FILE_RECEIPT")
            mutable.update { it.copy(notice = "Desktop confirmed the same saved operation. No replacement identifier was created. Refresh for current files.") }
        }
    }
    fun dismiss(operationId: String, acknowledged: Boolean) = request(allowOffline = true) {
        gateway.dismiss(operationId, acknowledged); current()
        mutable.update { it.copy(notice = "Local retry was dismissed. This does not cancel or confirm the desktop operation.") }
    }
}
