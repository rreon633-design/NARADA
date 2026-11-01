package com.narada.android.offline

import com.narada.android.chat.ChatAccess
import com.narada.android.chat.chatAccess
import com.narada.android.connection.ConnectionsCoordinator
import com.narada.android.di.ApplicationScope
import com.narada.android.transfers.TransfersCoordinator
import com.narada.android.transfers.isProtectedStorageFailure
import com.narada.connection.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OfflineRoot(val peerId: String, val rootId: String, val label: String)
data class OfflinePinRow(val id: String, val name: String, val available: Boolean, val tombstone: Boolean, val update: Boolean, val oldPairing: Boolean, val candidateId: String?, val candidateState: DownloadState?, val current: String?, val previous: List<String>) {
    override fun toString() = "OfflinePinRow([private presentation])"
}
data class OfflineUiState(val visible: Boolean = false, val peerId: String? = null, val rootId: String? = null, val access: ChatAccess? = null,
    val roots: List<OfflineRoot> = emptyList(), val entries: List<SyncEntry> = emptyList(), val pins: List<OfflinePinRow> = emptyList(),
    val busy: Boolean = false, val freshAt: Long? = null, val partialCount: Int? = null, val metadataError: String? = null, val scopeCurrent: Boolean = false, val networkEligible: Boolean = false,
    val error: String? = null, val viewEpoch: Long = 0,
) { override fun toString() = "OfflineUiState([private projection])" }
@Singleton class OfflineCoordinator @Inject constructor(
    private val repository: TrustRepository, private val gateway: SyncGateway, private val connections: ConnectionsCoordinator,
    private val transfers: TransfersCoordinator, transfersGateway: TransfersGateway, chunks: TransferChunks,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(OfflineUiState()); val state = mutable.asStateFlow()
    private val engine = OfflineEngine(repository, gateway, changed = { refresh() })
    private val downloads = DownloadEngine(repository, transfersGateway, chunks)
    private val control = Mutex(); private val generation = AtomicLong()
    @Volatile private var worker: Job? = null
    init {
        // Observe locally completed candidates without starting metadata or transfer networking.
        scope.launch { transfers.state.collect { if (mutable.value.visible && connections.state.value.foreground) control.withLock { refreshSafely() } } }
        scope.launch {
            var observed: ChatAccess? = null; var selected: String? = null
            connections.state.collect { c ->
                val id = mutable.value.peerId
                if (id != selected) { selected = id; observed = null }
                val next = chatAccess(c, id); val changed = observed != null && observed != next; observed = next
                if (!c.foreground || changed) { clear(); control.withLock { stopWorker() } }
            }
        }
    }
    private fun clear() { val turn = generation.incrementAndGet(); gateway.cancel(); worker?.cancel(); mutable.update { it.copy(entries = emptyList(), pins = emptyList(), roots = emptyList(), access = null, freshAt = null, partialCount = null, metadataError = null, scopeCurrent = false, networkEligible = false, viewEpoch = turn) } }
    private fun guard(turn: Long) { demand(turn == generation.get() && mutable.value.visible && connections.state.value.foreground, "SYNC_STOPPED") }
    private fun command(block: suspend (Long) -> Unit) {
        val turn = generation.get()
        scope.launch { control.withLock {
            try { guard(turn); demand(worker?.isActive != true, "SYNC_BUSY"); mutable.update { it.copy(error = null) }; block(turn); refresh() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { report(e, turn) }
        } }
    }
    fun visible(value: Boolean) {
        mutable.update { it.copy(visible = value) }
        if (!value) { clear(); scope.launch { control.withLock { stopWorker() } } } else reload()
    }
    fun reload() = command { }
    fun select(peerId: String, rootId: String) { clear(); mutable.update { it.copy(peerId = peerId, rootId = rootId) }; scope.launch { control.withLock { stopWorker(); refreshSafely() } } }
    fun refreshMetadata(forceSnapshot: Boolean = false) = command { turn ->
        val peer = mutable.value.peerId ?: fail("PEER_NOT_FOUND"); val root = mutable.value.rootId ?: fail("ROOT_NOT_FOUND")
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext().job
            try { engine.refresh(peer, root, true, forceSnapshot) { guard(turn) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { report(e, turn) }
            finally { scope.launch { control.withLock { if (worker === self) { worker = null; refreshSafely() } } } }
        }; worker = job; mutable.update { it.copy(busy = true) }; job.start()
    }
    fun stop() { clear(); scope.launch { control.withLock { stopWorker(); refreshSafely() } } }
    fun pin(objectId: String, confirmed: Boolean) = command {
        engine.pin(mutable.value.peerId ?: fail("PEER_NOT_FOUND"), mutable.value.rootId ?: fail("ROOT_NOT_FOUND"), objectId, confirmed); transfers.startQueued()
    }
    private suspend fun selected(id: String) { val p = repository.read()?.pin(id) ?: fail("PIN_NOT_FOUND"); demand(p.peerId == mutable.value.peerId && p.rootId == mutable.value.rootId, "SYNC_STOPPED") }
    fun refreshPin(id: String, confirmed: Boolean) = command { selected(id); engine.refreshPin(id, confirmed); transfers.startQueued() }
    fun adopt(id: String, confirmed: Boolean) = command { turn -> selected(id); withContext(Dispatchers.IO) { engine.adopt(id, downloads, confirmed) { guard(turn) } } }
    fun release(id: String, downloadId: String, confirmed: Boolean) = command { selected(id); engine.release(id, downloadId, confirmed) }
    fun unpin(id: String, confirmed: Boolean) = command { selected(id); engine.unpin(id, confirmed) }
    fun forgetMetadata(confirmed: Boolean) = command { engine.forgetMetadata(mutable.value.peerId ?: fail("PEER_NOT_FOUND"), mutable.value.rootId ?: fail("ROOT_NOT_FOUND"), confirmed) }
    private suspend fun stopWorker() { val old = worker; worker = null; gateway.cancel(); old?.cancelAndJoin(); mutable.update { it.copy(busy = false) } }
    private suspend fun refresh() {
        val turn = generation.get(); val doc = repository.read(); val c = connections.state.value
        if (!mutable.value.visible || !c.foreground || turn != generation.get()) { mutable.update { it.copy(entries = emptyList(), pins = emptyList(), roots = emptyList(), access = null, busy = worker?.isActive == true) }; return }
        val pairs = doc?.peers.orEmpty().flatMap { p -> p.remotePermissions.roots.filter { it.capabilities.containsAll(listOf("read", "sync_read")) }.map { p.id to it.root_id } } + doc?.syncCaches.orEmpty().map { it.peerId to it.rootId }
        val roots = pairs.distinct().map { (peer, root) -> OfflineRoot(peer, root, "${doc?.peer(peer)?.name ?: "Saved desktop"} · ${root.take(8)}") }
        val chosen = roots.find { it.peerId == mutable.value.peerId && it.rootId == mutable.value.rootId } ?: roots.find { it.peerId == doc?.preferredPeer } ?: roots.firstOrNull()
        val peer = chosen?.peerId?.let { doc?.peer(it) }
        demand(peer == c.document?.peers?.find { it.id == chosen?.peerId }, "SYNC_SCOPE_CHANGED")
        val cache = doc?.syncCaches?.find { it.peerId == chosen?.peerId && it.rootId == chosen?.rootId }
        val pins = doc?.pins.orEmpty().filter { it.peerId == chosen?.peerId && it.rootId == chosen?.rootId }.map { p ->
            val copy = p.current?.let { doc?.download(it) }; val candidate = p.candidate?.let { doc?.download(it) }
            OfflinePinRow(p.id, copy?.name ?: p.observed?.name ?: "Retained private copy", copy?.state == DownloadState.VERIFIED && copy.receipt != null, p.tombstone,
                p.observed != null && copy != null && p.observed?.version != copy.source.expected_version, p.pairingAttempt != peer?.attemptId,
                p.candidate, candidate?.state, p.current, p.previous)
        }
        val eligible = chosen != null && c.transportAllowed && c.networkAvailable && doc != null && runCatching { SyncRules.authority(doc, chosen.peerId, doc.peer(chosen.peerId).attemptId, chosen.rootId) }.isSuccess
        guard(turn)
        mutable.update { if (generation.get() != turn || !it.visible || !connections.state.value.foreground) it else it.copy(roots = roots, peerId = chosen?.peerId, rootId = chosen?.rootId, entries = cache?.entries.orEmpty(), pins = pins,
            access = chatAccess(c, chosen?.peerId), freshAt = cache?.refreshedAt, partialCount = cache?.work?.entries?.size, metadataError = cache?.error,
            busy = worker?.isActive == true, scopeCurrent = cache?.scope != null && cache.scope == peer?.let(SyncScope::from), networkEligible = eligible) }
    }
    private suspend fun refreshSafely() { try { refresh() } catch (e: Exception) { report(e) } }
    private fun report(e: Exception, turn: Long? = null) {
        if (e is CancellationException) return
        if (isProtectedStorageFailure(e)) { clear(); connections.fileStorageFailure(e) }
        if (turn == null || turn == generation.get() || isProtectedStorageFailure(e)) mutable.update { it.copy(error = (e as? ConnectionFailure)?.code ?: "SYNC_UNAVAILABLE") }
    }
}
