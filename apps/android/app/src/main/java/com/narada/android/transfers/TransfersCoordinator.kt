package com.narada.android.transfers

import com.narada.android.connection.ConnectionsCoordinator
import com.narada.android.di.ApplicationScope
import com.narada.connection.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ExportPicker(val transferId: String, val exportId: String)
data class TransfersUiState(
    val loading: Boolean = true, val records: List<DownloadRecord> = emptyList(), val activeId: String? = null,
    val error: String? = null, val visible: Boolean = false, val providerBusy: Boolean = false,
    val picker: ExportPicker? = null, val foreground: Boolean = false, val networkEligible: Boolean = false,
)
/** Accepted work belongs to the application, not a Composable/ViewModel. Foreground only: no service,
 * WorkManager transfer, boot auto-resume or guessed progress. Restart always requires explicit resume. */
@Singleton
class TransfersCoordinator @Inject constructor(
    private val repository: TrustRepository, private val gateway: TransfersGateway, private val storage: TransferChunks,
    private val connections: ConnectionsCoordinator, private val providers: ExportProviderFactory, private val providerIo: BoundedProviderIo,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(TransfersUiState())
    val state = mutable.asStateFlow()
    private val control = Mutex()
    private val engine = DownloadEngine(repository, gateway, storage, changed = { refresh() })
    private val exporter = TransferExport(repository, engine, storage, changed = { refresh() })
    private var recovered = false
    @Volatile private var worker: Job? = null
    @Volatile private var activeId: String? = null
    @Volatile private var exportJob: Job? = null
    private val pickerLock = Any()
    private var claimedPicker: String? = null
    init {
        providerIo.onIdle = { scope.launch { refreshSafely() } }
        command { ensureRecovered() }
        scope.launch {
            var observedEpoch: Long? = null
            connections.state.collect { s ->
                val changed = observedEpoch != null && observedEpoch != s.accessEpoch
                observedEpoch = s.accessEpoch
                mutable.update { it.copy(foreground = s.foreground, networkEligible = s.foreground && s.transportAllowed && s.networkAvailable) }
                if (!s.foreground || !s.transportAllowed || !s.networkAvailable || changed) {
                    if (!s.foreground) exportJob?.cancel()
                    // Closing only OUR content lease cannot cancel an unrelated Files request.
                    gateway.cancel()
                    control.withLock {
                        try {
                            ensureRecovered()
                            val local = mutable.value.records.find { it.id == activeId }?.stagedComplete == true
                            if (!s.foreground || changed || !local) stopWorker()
                            repository.read()?.downloads?.filter { it.state in setOf(DownloadState.RUNNING, DownloadState.QUEUED) && (!s.foreground || changed || !it.stagedComplete) }?.forEach { engine.pause(it.id, if (!s.foreground) "FOREGROUND_PAUSED" else "TRANSFER_ACCESS_CHANGED") }
                        }
                        catch (e: Exception) { report(e) }
                    }
                }
            }
        }
    }
    private suspend fun ensureRecovered() { if (!recovered) { engine.recover(); recovered = true }; refresh() }
    private fun command(block: suspend () -> Unit) { scope.launch { control.withLock {
        try { ensureRecovered(); mutable.update { it.copy(error = null) }; block(); refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { report(e) }
    } } }
    fun visible(value: Boolean) { mutable.update { it.copy(visible = value) }; if (value) scope.launch { refreshSafely() } }
    fun reload() = command { demand(!providerIo.busy && exportJob?.isActive != true, "PROVIDER_BUSY"); stopWorker(); synchronized(pickerLock) { claimedPicker = null; mutable.update { it.copy(picker = null) } }; recovered = false; ensureRecovered() }
    fun enqueue(choice: DownloadChoice, confirmed: Boolean) = command {
        demand(mutable.value.picker == null && !providerIo.busy, "PROVIDER_BUSY")
        engine.enqueue(choice.peerId, choice.pairingAttempt, choice.source, choice.name, choice.size, confirmed); pump()
    }
    fun startQueued() = command { pump() } // Only after an explicit pin/create-refresh consent.
    fun inputFailure(e: Exception) { report(e) }
    private suspend fun pump() {
        if (worker?.isActive == true || providerIo.busy || exportJob?.isActive == true || mutable.value.picker != null) return
        val next = repository.read()?.downloads?.firstOrNull { it.state == DownloadState.QUEUED } ?: return
        if (!mutable.value.foreground || !mutable.value.networkEligible && !next.stagedComplete) { pauseQueued("FOREGROUND_PAUSED"); return }
        activeId = next.id
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext().job
            var failure: String? = null
            try {
                engine.run(next.id) { demand(connections.state.value.foreground, "FOREGROUND_PAUSED") }
                try { engine.finish(next.id) } catch (e: CancellationException) { throw e }
                catch (e: Exception) { report(e) } // Saved private receipt is independent of a lost desktop finish ACK.
            } catch (e: CancellationException) { failure = "TRANSFER_STOPPED"; throw e }
            catch (e: Exception) { failure = code(e); report(e) }
            finally {
                withContext(NonCancellable) { try { engine.pause(next.id, failure); refresh() } catch (e: Exception) { report(e) } }
                scope.launch { control.withLock {
                    if (worker === self) { worker = null; activeId = null; refreshSafely(); try { pump() } catch (e: Exception) { report(e) } }
                } }
            }
        }
        worker = job; refresh(); job.start()
    }
    private suspend fun stopWorker() {
        val old = worker; worker = null; activeId = null
        gateway.cancel(); old?.cancelAndJoin()
    }
    private suspend fun pauseQueued(reason: String) {
        repository.read()?.downloads?.filter { it.state in setOf(DownloadState.QUEUED, DownloadState.RUNNING) }?.forEach { engine.pause(it.id, reason) }
    }
    fun pause(id: String) = command { if (activeId == id) stopWorker(); engine.pause(id); pump() }
    fun pauseAll() = command { stopWorker(); pauseQueued("USER_PAUSED") }
    fun resume(id: String, confirmed: Boolean) = command { engine.queueResume(id, confirmed); pump() }
    fun cancel(id: String, confirmed: Boolean) = command { demand(!providerIo.busy, "PROVIDER_BUSY"); if (activeId == id) stopWorker(); engine.cancel(id, confirmed); pump() }
    fun remove(id: String, confirmed: Boolean) = command { demand(!providerIo.busy && mutable.value.picker == null, "PROVIDER_BUSY"); if (activeId == id) stopWorker(); engine.remove(id, confirmed); pump() }
    fun finish(id: String) = command { engine.finish(id) }
    fun prepareExport(id: String, name: String, confirmed: Boolean) = command {
        demand(!providerIo.busy && exportJob?.isActive != true && mutable.value.picker == null, "PROVIDER_BUSY")
        stopWorker(); pauseQueued("EXPORT_REVIEW")
        val request = exporter.prepare(id, name, confirmed)
        synchronized(pickerLock) { claimedPicker = null; mutable.update { it.copy(picker = ExportPicker(id, request.id)) } }
    }
    /** A rotation may recompose the effect; only one picker launch per in-memory ticket. No saved
     * URI/name/ticket fallback and no binding a process-restored picker reply to a new transfer. */
    fun claimPicker(exportId: String): Boolean = synchronized(pickerLock) {
        if (mutable.value.picker?.exportId != exportId || claimedPicker == exportId) false else { claimedPicker = exportId; true }
    }
    fun pickerResult(exportId: String, tree: String?, flags: Int) = command {
        val ticket = synchronized(pickerLock) { mutable.value.picker?.takeIf { it.exportId == exportId && claimedPicker == exportId }?.also { mutable.update { it.copy(picker = null) }; claimedPicker = null } } ?: return@command
        if (tree == null) { exporter.pickerCancelled(ticket.transferId, ticket.exportId); return@command }
        if (!connections.state.value.foreground || providerIo.busy) { exporter.pickerCancelled(ticket.transferId, ticket.exportId); fail("FOREGROUND_PAUSED") }
        val provider = try { providers.create(tree, flags) } catch (e: Exception) { exporter.pickerCancelled(ticket.transferId, ticket.exportId); throw e }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                providerIo.run(provider::cancel) { guard -> exporter.execute(ticket.transferId, ticket.exportId, tree, provider) {
                    guard(); demand(connections.state.value.foreground, "FOREGROUND_PAUSED")
                } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
            finally {
                withContext(NonCancellable) {
                    try { exporter.interrupted(ticket.transferId, ticket.exportId) } catch (e: Exception) { report(e) }
                    exportJob = null; refreshSafely()
                }
            }
        }
        exportJob = job; mutable.update { it.copy(providerBusy = true) }; job.start()
    }
    fun stopExport() { exportJob?.cancel() }
    fun dismissExport(id: String, exportId: String, acknowledged: Boolean) = command {
        demand(!providerIo.busy && exportJob?.isActive != true, "PROVIDER_BUSY")
        exporter.dismiss(id, exportId, acknowledged)
    }
    fun forgetExport(id: String, exportId: String, confirmed: Boolean) = command {
        demand(!providerIo.busy && exportJob?.isActive != true && mutable.value.picker == null, "PROVIDER_BUSY")
        exporter.forget(id, exportId, confirmed)
    }
    private suspend fun refresh() {
        val doc = repository.read()
        mutable.update { it.copy(loading = false, records = doc?.downloads.orEmpty(), activeId = activeId, providerBusy = providerIo.busy || exportJob?.isActive == true) }
    }
    private suspend fun refreshSafely() { try { refresh() } catch (e: Exception) { report(e) } }
    private fun code(e: Exception) = (e as? ConnectionFailure)?.code ?: "LOCAL_STORAGE_UNAVAILABLE"
    private fun report(e: Exception) {
        if (e is CancellationException) return
        if (isProtectedStorageFailure(e)) connections.fileStorageFailure(e)
        mutable.update { it.copy(loading = false, error = code(e)) }
    }
}
