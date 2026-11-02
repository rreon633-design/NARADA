package com.narada.android.chat

import com.narada.android.connection.ConnectionsCoordinator
import com.narada.android.connection.ConnectionsUiState
import com.narada.android.connection.connectionMessage
import com.narada.android.di.ApplicationScope
import com.narada.android.transfers.isProtectedStorageFailure
import com.narada.connection.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only a private, ephemeral presentation projection. Never an Olm account, pickle or wire ticket. */
data class ChatRow(val id: String, val text: String, val outgoing: Boolean, val status: ChatStatus, val acknowledged: Boolean, val unread: Boolean, val oldPairing: Boolean, val attempts: Int, val error: String?, val attachmentName: String? = null, val attachmentSize: Long? = null) {
    override fun toString() = "ChatRow([private presentation redacted])"
}
data class ChatPeer(val id: String, val name: String, val active: Boolean)
data class ChatAccess(val epoch: Long, val foreground: Boolean, val allowed: Boolean, val route: Boolean, val peer: TrustedPeer?)
fun chatAccess(s: ConnectionsUiState, peerId: String?) = ChatAccess(s.accessEpoch, s.foreground, s.transportAllowed, s.networkAvailable, s.document?.peers?.find { it.id == peerId })
data class ChatUiState(
    val loading: Boolean = false, val visible: Boolean = false, val foreground: Boolean = false, val busy: Boolean = false,
    val peers: List<ChatPeer> = emptyList(), val peerId: String? = null, val access: ChatAccess? = null,
    val enabled: Boolean = false, val renewal: Boolean = false, val networkEligible: Boolean = false,
    val rows: List<ChatRow> = emptyList(), val draft: String = "", val composerEpoch: Long = 0, val viewEpoch: Long = 0,
    val retained: Int = 0, val error: String? = null, val liveUpdates: Boolean = false, val receiving: Boolean = false,
) { override fun toString() = "ChatUiState([private presentation redacted])" }
fun interface ChatNotice { suspend fun delivered() }

/** One bounded batch and one opt-in, receive-only foreground scheduler. No service, boot sender
 * or UI-owned socket. Leaving Chat, foreground or authority revokes ephemeral live consent. */
@Singleton
class ChatCoordinator @Inject constructor(
    private val repository: TrustRepository, private val crypto: OlmCryptography, private val gateway: ChatGateway,
    private val connections: ConnectionsCoordinator, private val notice: ChatNotice,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val engine = ChatEngine(repository, crypto)
    private val mutable = MutableStateFlow(ChatUiState())
    val state = mutable.asStateFlow()
    private val control = Mutex()
    private val generation = AtomicLong()
    @Volatile private var worker: Job? = null
    @Volatile private var liveJob: Job? = null
    @Volatile private var receivingOnly = false
    private var recovered = false
    init {
        scope.launch {
            var observed: ChatAccess? = null
            var observedPeer: String? = null
            connections.state.collect { c ->
                val id = mutable.value.peerId
                if (id != observedPeer) { observedPeer = id; observed = null }
                val next = chatAccess(c, id)
                val changed = observed != null && observed != next
                observed = next
                mutable.update { it.copy(foreground = c.foreground, networkEligible = eligible(c, it.peerId)) }
                if (!c.foreground || changed) {
                    clear(if (c.foreground) "CHAT_ACCESS_CHANGED" else null)
                    control.withLock { try { stopWorker() } catch (e: Exception) { report(e) } }
                }
            }
        }
    }
    private fun eligible(c: ConnectionsUiState, id: String?): Boolean {
        val p = c.document?.peers?.find { it.id == id } ?: return false
        return c.foreground && c.transportAllowed && c.networkAvailable && p.status == TrustStatus.ACTIVE && !p.blocked && p.failures < 6 && p.remotePermissions.chat && ChatRules.CAPABILITY in p.remoteOperations && c.document.peers.all { it.status in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }
    }
    private fun clear(error: String? = null) {
        stopLive()
        val epoch = generation.incrementAndGet(); gateway.cancel(); worker?.cancel()
        mutable.update { it.copy(rows = emptyList(), draft = "", peers = emptyList(), access = null, enabled = false, renewal = false, retained = 0, error = error, viewEpoch = epoch) }
    }
    private fun guard(turn: Long) {
        demand(generation.get() == turn && mutable.value.visible && connections.state.value.foreground, "CHAT_STOPPED")
    }
    private suspend fun recover() {
        if (!recovered && repository.read() != null) { engine.recover(); recovered = true }
    }
    private fun command(block: suspend (Long) -> Unit) {
        val turn = generation.get()
        scope.launch { control.withLock {
            try {
                guard(turn); recover(); mutable.update { it.copy(error = null, busy = true) }
                block(turn); refresh(turn)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e, turn) }
            finally { if (turn == generation.get()) mutable.update { it.copy(busy = worker?.isActive == true, receiving = receivingOnly && worker?.isActive == true) } }
        } }
    }
    fun visible(value: Boolean) {
        mutable.update { it.copy(visible = value) }
        if (!value) { clear(); scope.launch { control.withLock { try { stopWorker() } catch (e: Exception) { report(e) } } } }
        else reload()
    }
    fun reload() = command { turn -> stopLive(); stopWorker(); recover(); refresh(turn) }
    fun select(peerId: String) {
        clear(); mutable.update { it.copy(peerId = peerId) }
        command { turn -> stopWorker(); refresh(turn) }
    }
    fun enable(confirmed: Boolean) = command { turn ->
        demand(worker?.isActive != true, "CHAT_BUSY")
        engine.enablePeer(selected(), confirmed) { guard(turn) }
    }
    fun saveDraft(text: String) = command { turn ->
        demand(worker?.isActive != true, "CHAT_BUSY"); engine.saveDraft(selected(), text) { guard(turn) }
        mutable.update { it.copy(composerEpoch = it.composerEpoch + 1) }
    }
    fun send(id: String, text: String) = command { turn ->
        demand(worker?.isActive != true, "CHAT_BUSY"); engine.queue(selected(), id, text) { guard(turn) }
        mutable.update { it.copy(composerEpoch = it.composerEpoch + 1) }; startBatch(turn, outgoingId = id)
    }
    fun sync() = command { turn -> startBatch(turn) }
    fun receive() = command { turn -> startBatch(turn, receiveOnly = true) }
    private fun stopLive() {
        liveJob?.cancel(); liveJob = null
        mutable.update { it.copy(liveUpdates = false) }
    }
    /** Consent is memory-only for this visible conversation. Receiving never drains the outbox. */
    fun liveUpdates(enabled: Boolean) = command { turn ->
        stopLive()
        if (!enabled) {
            if (receivingOnly) stopWorker()
        } else {
            demand(mutable.value.enabled && !mutable.value.renewal && eligible(connections.state.value, selected()), "CHAT_OFFLINE")
            val peer = selected()
            mutable.update { it.copy(liveUpdates = true) }
            liveJob = scope.launch {
                while (isActive && generation.get() == turn && mutable.value.liveUpdates && mutable.value.peerId == peer) {
                    control.withLock {
                        if (generation.get() != turn || !mutable.value.liveUpdates) return@withLock
                        if (!eligible(connections.state.value, peer)) { stopLive(); return@withLock }
                        if (worker?.isActive != true) {
                            try { startBatch(turn, receiveOnly = true) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { report(e, turn) }
                        }
                    }
                    delay(5000) // Shared gateway admission still bounds every request; never overlap batches.
                }
            }
        }
    }
    fun stop() { clear("CHAT_INTERRUPTED"); command { turn -> stopWorker(); refresh(turn) } }
    fun retry(id: String, reviewed: Boolean) = command { turn ->
        demand(worker?.isActive != true, "CHAT_BUSY"); requireSelected(id); engine.retry(id, reviewed); guard(turn)
    }
    fun cancel(id: String, reviewed: Boolean) = command { turn ->
        demand(worker?.isActive != true, "CHAT_BUSY"); requireSelected(id); engine.cancel(id, reviewed); guard(turn)
    }
    fun markRead() = command { engine.markRead(selected()) }
    private fun selected() = mutable.value.peerId ?: fail("PEER_NOT_FOUND")
    private suspend fun requireSelected(id: String) { demand(repository.read()?.chat?.message(id)?.peerId == selected(), "CHAT_STOPPED") }
    private suspend fun startBatch(turn: Long, receiveOnly: Boolean = false, outgoingId: String? = null) {
        guard(turn); demand(worker?.isActive != true, "CHAT_BUSY"); val peerId = selected()
        demand(eligible(connections.state.value, peerId), "CHAT_OFFLINE")
        val before = repository.read()?.chat?.messages?.count { it.direction == ChatDirection.INCOMING } ?: 0
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext().job
            try {
                if (receiveOnly) engine.receiveOnly(peerId, gateway) { guard(turn) }
                else if (outgoingId != null) engine.sendOne(peerId, outgoingId, gateway) { guard(turn) }
                else engine.sync(peerId, gateway) { guard(turn) }
                guard(turn)
                val after = repository.read()?.chat?.messages?.count { it.direction == ChatDirection.INCOMING } ?: 0
                if (after > before) notice.delivered() // No private values cross the notification interface.
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e, turn) }
            finally {
                withContext(NonCancellable) { try { engine.recover() } catch (e: Exception) { report(e) } }
                scope.launch { control.withLock {
                    if (worker === self) { worker = null; receivingOnly = false; if (turn == generation.get()) { mutable.update { it.copy(busy = false, receiving = false) }; try { refresh(turn) } catch (e: Exception) { report(e, turn) } } }
                } }
            }
        }
        worker = job; receivingOnly = receiveOnly; mutable.update { it.copy(busy = true, receiving = receiveOnly) }; job.start()
    }
    private suspend fun stopWorker() {
        val old = worker; worker = null; receivingOnly = false; gateway.cancel(); old?.cancelAndJoin()
        if (repository.read()?.chat != null) engine.recover()
        mutable.update { it.copy(busy = false, receiving = false) }
    }
    private suspend fun refresh(turn: Long) {
        guard(turn); val doc = repository.read(); guard(turn)
        val c = connections.state.value
        val peers = doc?.peers.orEmpty().filter { it.status == TrustStatus.ACTIVE || doc?.chat?.bindings?.any { b -> b.peerId == it.id } == true }.map { ChatPeer(it.id, it.name, it.status == TrustStatus.ACTIVE) }
        val id = mutable.value.peerId?.takeIf { selected -> peers.any { it.id == selected } } ?: doc?.preferredPeer?.takeIf { selected -> peers.any { it.id == selected } } ?: peers.firstOrNull()?.id
        val b = doc?.chat?.bindings?.find { it.peerId == id }
        val p = doc?.peers?.find { it.id == id }
        if (p != null) demand(c.document?.peers?.find { it.id == id } == p, "CHAT_ACCESS_CHANGED")
        val rows = doc?.chat?.messages.orEmpty().filter { it.peerId == id }.map { ChatRow(it.id, it.text, it.direction == ChatDirection.OUTGOING, it.status, it.acknowledged, it.unread, it.pairingAttempt != p?.attemptId, it.attempts, it.error, it.attachment?.name, it.attachment?.plaintext_size) }
        // No stale authority projection can be rendered; the Composable checks this stamp too.
        val access = chatAccess(c, id)
        guard(turn)
        mutable.update { it.copy(loading = false, peers = peers, peerId = id, access = access, enabled = b != null, renewal = b != null && b.pairingAttempt != p?.attemptId,
            rows = rows, draft = doc?.chat?.drafts?.find { d -> d.peerId == id }?.text.orEmpty(), retained = doc?.chat?.messages?.size ?: 0,
            foreground = c.foreground, networkEligible = eligible(c, id), busy = worker?.isActive == true, receiving = receivingOnly && worker?.isActive == true) }
    }
    private fun report(e: Exception, turn: Long? = null) {
        if (e is CancellationException) return
        if (turn == null || turn == generation.get()) stopLive() // An error requires review, not unattended retries.
        if (isProtectedStorageFailure(e)) { clear(); connections.fileStorageFailure(e) }
        if (turn == null || turn == generation.get() || isProtectedStorageFailure(e)) mutable.update { it.copy(loading = false, error = (e as? ConnectionFailure)?.code ?: "CHAT_UNAVAILABLE") }
    }
}

fun chatMessage(code: String): String = when (code) {
    "CHAT_NOT_ENABLED" -> "Enable protected text chat for this approved desktop. No account is created automatically."
    "CHAT_OFFLINE", "CHAT_SCOPE_CHANGED", "CHAT_UNSUPPORTED", "ACCESS_DENIED" -> "Keep NARADA visible on an allowed local network. The saved desktop must grant chat and advertise the compatible protocol. Local saved history remains separate."
    "CHAT_STOPPED", "CHAT_INTERRUPTED", "CHAT_ACCESS_CHANGED", "FILE_ACCESS_CHANGED" -> "Work stopped because visibility or authority changed. Load protected history, review any unknown outcome, then explicitly sync again."
    "CHAT_KEY_CHANGED", "CHAT_KEY_INVALID" -> "The messaging identity did not match the retained, transport-bound key. No key was replaced. Review both devices; do not clear data to bypass this check."
    "CHAT_PAIRING_CHANGED" -> "This message belongs to an earlier pairing. It is retained but cannot be resent under new authority."
    "CHAT_HISTORY_FULL", "CHAT_PEER_LIMIT", "CHAT_SESSION_LIMIT", "CHAT_STATE_LIMIT" -> "The bounded protected store is full. Existing history, unknown outcomes and crypto state were kept; nothing was silently evicted."
    "CRYPTO_UNAVAILABLE", "CRYPTO_INCOMPATIBLE" -> "The bundled Olm library could not be used. There is no plaintext, downloaded-library or alternative-ratchet fallback."
    "CHAT_REMOTE_STORAGE", "CHAT_REMOTE_CRYPTO" -> "The desktop's protected messaging store is unavailable. This does not reset or replace this phone's keys."
    "ATTACHMENT_NOT_VERIFIED" -> "The attachment ciphertext is not privately verified or was explicitly stopped. No attachment key or partial file was sent."
    "MESSAGE_RETRY_LIMIT" -> "The retry budget or four-second spacing was reached. Review the saved request before enabling another exact-ciphertext attempt."
    "MESSAGE_ID_CONFLICT", "INVALID_RECEIPT", "CHAT_DECRYPT_FAILED", "CHAT_SESSION_FAILED", "CHAT_SESSION_MISSING", "INVALID_MESSAGE" -> "The message, retained identifier or receipt could not be verified. No success, replacement message or plaintext fallback was inferred."
    "CHAT_BUSY" -> "Wait for this explicit batch or stop it before changing the conversation."
    else -> connectionMessage(code)
}
