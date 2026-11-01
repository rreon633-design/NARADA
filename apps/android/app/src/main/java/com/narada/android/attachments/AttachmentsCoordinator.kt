package com.narada.android.attachments

import com.narada.android.chat.ChatAccess
import com.narada.android.chat.ChatCoordinator
import com.narada.android.chat.chatAccess
import com.narada.android.connection.ConnectionsCoordinator
import com.narada.android.di.ApplicationScope
import com.narada.android.transfers.*
import com.narada.connection.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AttachmentPicker(val id: String, val exportId: String? = null) { val ticketId: String get() = exportId ?: id }
data class AttachmentExportRow(val id: String, val name: String, val state: ExportState, val error: String?)
data class AttachmentRow(val id: String, val name: String, val size: Long?, val outgoing: Boolean, val state: AttachmentState, val available: Boolean, val remoteStored: Boolean, val receivedChunks: Int, val uploadedChunks: Int, val totalChunks: Int, val messageDelivered: Boolean, val oldPairing: Boolean, val error: String?, val exports: List<AttachmentExportRow>) {
    override fun toString() = "AttachmentRow([private presentation])"
}
data class AttachmentsUiState(val visible: Boolean = false, val peerId: String? = null, val access: ChatAccess? = null, val rows: List<AttachmentRow> = emptyList(), val busy: Boolean = false, val providerBusy: Boolean = false, val picker: AttachmentPicker? = null, val error: String? = null, val viewEpoch: Long = 0) {
    override fun toString() = "AttachmentsUiState([private presentation])"
}
@Singleton class AttachmentsCoordinator @Inject constructor(
    private val repository: TrustRepository, crypto: AgeCryptography, @AttachmentData storage: TransferChunks,
    private val gateway: AttachmentsGateway, private val connections: ConnectionsCoordinator, private val chat: ChatCoordinator,
    private val imports: ImportProviderFactory, private val providers: ExportProviderFactory, private val providerIo: BoundedProviderIo,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(AttachmentsUiState()); val state = mutable.asStateFlow()
    private val engine = AttachmentsEngine(repository, crypto, storage, gateway, changed = { refresh() })
    private val exporter = AttachmentExport(repository, engine, changed = { refresh() })
    private val control = Mutex(); private val generation = AtomicLong(); private val pickerLock = Any()
    @Volatile private var worker: Job? = null
    private var claimed: String? = null; private var recovered = false
    init {
        providerIo.addIdleListener { scope.launch { refreshSafely() } }
        // Local projection refresh only: a chat receipt never schedules ciphertext upload.
        scope.launch { chat.state.collect { if (mutable.value.visible && connections.state.value.foreground) control.withLock { refreshSafely() } } }
        scope.launch {
            var observed: ChatAccess? = null; var selected: String? = null
            connections.state.collect { c ->
                val id = mutable.value.peerId
                if (selected != id) { selected = id; observed = null }
                val next = chatAccess(c, id); val changed = observed != null && observed != next; observed = next
                if (!c.foreground || changed) {
                    clear() // Keep ONLY a non-secret, still-live picker ticket across the external picker.
                    control.withLock { try { stopWorker() } catch (e: Exception) { report(e) } }
                }
            }
        }
    }
    private fun clear() { val turn = generation.incrementAndGet(); gateway.cancel(); worker?.cancel(); mutable.update { it.copy(rows = emptyList(), access = null, viewEpoch = turn) } }
    private fun guard(turn: Long) { demand(generation.get() == turn && mutable.value.visible && connections.state.value.foreground, "ATTACHMENT_STOPPED") }
    private suspend fun recover() { if (!recovered) { engine.recover(); recovered = true } }
    private fun command(block: suspend (Long) -> Unit) {
        val turn = generation.get()
        scope.launch { control.withLock { try { guard(turn); recover(); mutable.update { it.copy(error = null) }; block(turn); refresh() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { report(e, turn) } } }
    }
    fun visible(value: Boolean) {
        mutable.update { it.copy(visible = value) }
        if (!value) { clear(); scope.launch { control.withLock { try { stopWorker(); abandonPicker() } catch (e: Exception) { report(e) } } } }
        else reload()
    }
    fun select(peerId: String?) {
        if (mutable.value.peerId == peerId) return
        clear(); mutable.update { it.copy(peerId = peerId) }
        command { stopWorker(); abandonPicker() }
    }
    fun reload() = command { demand(!providerIo.busy, "PROVIDER_BUSY"); stopWorker(); abandonPicker() }
    private suspend fun abandonPicker() {
        val p = synchronized(pickerLock) { mutable.value.picker.also { claimed = null; mutable.update { s -> s.copy(picker = null) } } } ?: return
        if (p.exportId == null) engine.pickerCancelled(p.id) else exporter.pickerCancelled(p.id, p.exportId)
    }
    private fun idle() { demand(worker?.isActive != true && !providerIo.busy && mutable.value.picker == null, "ATTACHMENT_BUSY") }
    fun prepareImport(text: String, confirmed: Boolean) = command { turn ->
        idle(); val peer = mutable.value.peerId ?: fail("PEER_NOT_FOUND")
        val r = engine.prepareImport(peer, text, confirmed) { guard(turn) }
        try { guard(turn); synchronized(pickerLock) { guard(turn); claimed = null; mutable.update { it.copy(picker = AttachmentPicker(r.id)) } } }
        catch (e: Exception) { withContext(NonCancellable) { settlePicker(AttachmentPicker(r.id)) }; throw e }
    }
    fun receive(messageId: String, confirmed: Boolean) = command { turn ->
        idle(); val m = repository.read()?.chat?.message(messageId) ?: fail("MESSAGE_UNAVAILABLE")
        demand(m.peerId == mutable.value.peerId, "ATTACHMENT_STOPPED")
        val old = repository.read()?.attachments?.find { it.messageId == messageId }
        val r = old ?: engine.enqueueReceive(messageId, confirmed)
        launchWorker(turn) { engine.run(r.id, confirmed) { guard(turn) } }
    }
    fun run(id: String, confirmed: Boolean) = command { turn -> idle(); selected(id); launchWorker(turn) { engine.run(id, confirmed) { guard(turn) } } }
    fun stop() { clear(); command { stopWorker() } }
    private suspend fun selected(id: String) { demand(repository.read()?.attachment(id)?.peerId == mutable.value.peerId, "ATTACHMENT_STOPPED") }
    fun cancel(id: String, confirmed: Boolean) = command { idle(); selected(id); engine.cancel(id, confirmed); chat.reload() }
    fun remove(id: String, confirmed: Boolean) = command { idle(); selected(id); engine.remove(id, confirmed) }
    fun prepareExport(id: String, name: String, confirmed: Boolean) = command { turn ->
        idle(); selected(id); val e = exporter.prepare(id, name, confirmed)
        try { guard(turn); synchronized(pickerLock) { guard(turn); claimed = null; mutable.update { it.copy(picker = AttachmentPicker(id, e.id)) } } }
        catch (error: Exception) { withContext(NonCancellable) { settlePicker(AttachmentPicker(id, e.id)) }; throw error }
    }
    fun claimPicker(ticketId: String): Boolean = synchronized(pickerLock) {
        if (mutable.value.picker?.ticketId != ticketId || claimed == ticketId) false else { claimed = ticketId; true }
    }
    fun pickerResult(ticketId: String, uri: String?, flags: Int) = command { turn ->
        val ticket = synchronized(pickerLock) { mutable.value.picker?.takeIf { it.ticketId == ticketId && claimed == ticketId }?.also { claimed = null; mutable.update { s -> s.copy(picker = null) } } } ?: return@command
        try {
            selected(ticket.id)
            if (uri == null) { settlePicker(ticket); return@command }
            demand(!providerIo.busy, "PROVIDER_BUSY")
            if (ticket.exportId == null) {
                // Factory construction is app-owned and non-I/O; all provider methods, including
                // close(), run inside the one bounded/quarantined worker after durable admission.
                val provider = imports.create(uri, flags)
                engine.admitImport(ticket.id, uri) { guard(turn) }
                launchWorker(turn, after = { engine.interrupted(ticket.id, "IMPORT_INTERRUPTED"); chat.reload() }) {
                    providerIo.run(provider::cancel) { live ->
                        try {
                            guard(turn); live(); val (name, stream) = provider.open()
                            engine.import(ticket.id, name, stream) { live(); guard(turn) }
                        } finally { provider.close() }
                    }
                }
            } else {
                val provider = providers.create(uri, flags)
                launchWorker(turn, after = { exporter.interrupted(ticket.id, ticket.exportId) }) {
                    providerIo.run(provider::cancel) { live -> exporter.execute(ticket.id, ticket.exportId, uri, provider) { live(); guard(turn) } }
                }
            }
        } catch (e: Exception) { withContext(NonCancellable) { settlePicker(ticket) }; throw e }
    }
    private suspend fun settlePicker(ticket: AttachmentPicker) {
        if (ticket.exportId != null) exporter.interrupted(ticket.id, ticket.exportId)
        else {
            val r = repository.read()?.attachments?.find { it.id == ticket.id } ?: return
            if (r.state == AttachmentState.PICKER_PENDING) engine.pickerCancelled(ticket.id)
            else engine.interrupted(ticket.id, "IMPORT_INTERRUPTED")
        }
    }
    fun dismissExport(id: String, exportId: String, confirmed: Boolean) = command { idle(); selected(id); exporter.dismiss(id, exportId, confirmed) }
    fun forgetExport(id: String, exportId: String, confirmed: Boolean) = command { idle(); selected(id); exporter.forget(id, exportId, confirmed) }
    private fun launchWorker(turn: Long, after: suspend () -> Unit = {}, action: suspend () -> Unit) {
        demand(worker?.isActive != true, "ATTACHMENT_BUSY")
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext().job
            try { guard(turn); action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e, turn) }
            finally {
                withContext(NonCancellable) { try { after() } catch (e: Exception) { report(e) } }
                scope.launch { control.withLock { if (worker === self) { worker = null; refreshSafely() } } }
            }
        }
        worker = job; mutable.update { it.copy(busy = true) }; job.start()
    }
    private suspend fun stopWorker() { val old = worker; worker = null; gateway.cancel(); old?.cancelAndJoin(); mutable.update { it.copy(busy = false, providerBusy = providerIo.busy) } }
    private suspend fun refresh() {
        val turn = generation.get(); val doc = repository.read(); val c = connections.state.value; val id = mutable.value.peerId
        val safe = mutable.value.visible && c.foreground && turn == generation.get() && doc?.peers?.find { it.id == id } == c.document?.peers?.find { it.id == id }
        val rows = if (!safe) emptyList() else doc?.attachments.orEmpty().filter { it.peerId == id }.map { r ->
            AttachmentRow(r.id, r.secret?.name ?: "Pending private import", r.secret?.plaintext_size, r.direction == ChatDirection.OUTGOING, r.state, r.available, r.remoteStored,
                r.chunks.size, r.uploaded.size, r.secret?.descriptor?.hashes?.size ?: 0, doc?.chat?.messages?.find { it.id == r.messageId }?.status == ChatStatus.DELIVERED,
                r.pairingAttempt != doc?.peers?.find { it.id == id }?.attemptId, r.error, r.exports.map { AttachmentExportRow(it.id, it.name, it.state, it.error) })
        }
        mutable.update {
            if (generation.get() != turn || !it.visible || it.peerId != id || !connections.state.value.foreground) it
            else it.copy(rows = rows, access = if (safe) chatAccess(c, id) else null, busy = worker?.isActive == true, providerBusy = providerIo.busy)
        }
    }
    private suspend fun refreshSafely() { try { refresh() } catch (e: Exception) { report(e) } }
    private fun report(e: Exception, turn: Long? = null) {
        if (e is CancellationException) return
        if (isProtectedStorageFailure(e)) { clear(); connections.fileStorageFailure(e) }
        if (turn == null || turn == generation.get() || isProtectedStorageFailure(e)) mutable.update { it.copy(error = (e as? ConnectionFailure)?.code ?: "ATTACHMENT_UNAVAILABLE") }
    }
}
