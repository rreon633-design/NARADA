package com.narada.android.connection

import com.narada.android.data.SettingsStore
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
import java.util.concurrent.atomic.AtomicLong

/** Public UI state contains no invitation token or Olm account/session/message state. Connected is volatile, never restored. */
data class InvitationPreview(val name: String, val fingerprint: String, val endpoint: String, val expiresAt: Long)
data class ConnectionsUiState(
    val loading: Boolean = true, val document: TrustDocument? = null, val protection: String? = null,
    val preview: InvitationPreview? = null, val busy: Boolean = false, val foreground: Boolean = false,
    val networkAvailable: Boolean = false, val connectedPeer: String? = null, val accessEpoch: Long = 0, val transportAllowed: Boolean = false,
    val status: String = "Offline", val error: String? = null, val pairingInProgress: Boolean = false,
)
@Singleton
class ConnectionsCoordinator @Inject constructor(
    private val repository: TrustRepository, private val connector: PeerConnector,
    private val network: ConnectionNetworkPolicy, private val discovery: DiscoveryControl, private val settings: SettingsStore,
    @param:ApplicationScope private val scope: CoroutineScope,
    private val contentAdmission: ContentAdmission = ContentAdmission(),
) {
    private val engine = PairingEngine(repository, connector)
    private val mutable = MutableStateFlow(ConnectionsUiState())
    val state = mutable.asStateFlow()
    private val commands = Mutex()
    private val generation = AtomicLong()
    private var networkJob: Job? = null
    @Volatile private var contentLease: String? = null
    private var ticket: PairCode? = null // Memory only; deliberately not SavedState/DataStore/Room.
    private var expiryJob: Job? = null
    @Volatile private var storageBlocked = false
    init { network.onRouteChanged = { foregroundChanged(network.foreground) } }

    fun foregroundChanged(value: Boolean) {
        network.foreground = value
        discovery.stop() // Any route/lifecycle invalidation discards stale, untrusted hints.
        invalidate(if (value) "Checking local network" else "Paused while app is not visible")
        if (!value) { ticket = null; expiryJob?.cancel(); discovery.stop(); mutable.update { it.copy(preview = null) } }
        scope.launch {
            commands.withLock {
                networkJob?.cancelAndJoin(); networkJob = null
                if (!network.foreground) { ticket = null; expiryJob?.cancel(); mutable.update { it.copy(preview = null) } }
                try { refresh(); resumeIfEligible() } catch (failure: Exception) { showFailure(failure) }
            }
        }
    }
    private fun invalidate(status: String) {
        generation.incrementAndGet(); connector.closeAll(); networkJob?.cancel()
        mutable.update { it.copy(connectedPeer = null, foreground = network.foreground, networkAvailable = network.available(), accessEpoch = generation.get(), transportAllowed = network.allowed, status = status, pairingInProgress = false) }
    }
    private fun command(action: suspend () -> Unit) { scope.launch { executeCommand(action) } }
    private suspend fun executeCommand(action: suspend () -> Unit) = commands.withLock {
        mutable.update { it.copy(busy = true, error = null) }
        try { action(); refresh() } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { showFailure(failure) }
        finally { mutable.update { it.copy(busy = false) } }
    }
    fun initialize() = command { repository.initialize() }
    /** One explicit user action prepares local QR input; never starts a claim or replaces keys.
     * Losing the foreground/route while storage is opening drops the launch, not the identity. */
    suspend fun preparePairingInput(): Boolean {
        val turn = generation.get() // Capture before waiting for another command.
        return commands.withLock {
            if (turn != generation.get()) return@withLock false
            mutable.update { it.copy(busy = true, error = null) }
            fun current() = turn == generation.get() && network.foreground && network.available() && !storageBlocked
            try {
                demand(!storageBlocked, "STORAGE_UNAVAILABLE")
                demand(network.foreground, "NETWORK_DISABLED")
                demand(network.available(), "LOCAL_NETWORK_UNAVAILABLE")
                val doc = repository.read()
                if (!current()) return@withLock false
                if (mutable.value.pairingInProgress || ticket != null || doc?.peers.orEmpty().any { it.status !in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }) {
                    mutable.update { it.copy(error = "PAIRING_IN_PROGRESS") }
                    return@withLock false
                }
                if (doc == null) repository.initialize()
                repository.identity() // Validate the existing transport key too; never recreate it.
                if (!current()) return@withLock false
                if (doc?.enabled != true) repository.mutate { it.copy(enabled = true) }
                if (!current()) return@withLock false
                refresh()
                if (!current()) return@withLock false
                mutable.update { it.copy(status = "Ready to scan a computer QR") }
                true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { showFailure(failure); false }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun reload() = command { repository.read(); storageBlocked = false; mutable.update { it.copy(status = "Protected state reopened · not connected") }; refresh(); resumeIfEligible() }
    fun enable(enabled: Boolean) = command {
        if (!enabled) network.allowed = false
        invalidate(if (enabled) "Ready to connect" else "Connections disabled"); networkJob?.cancelAndJoin(); networkJob = null; discovery.stop()
        if (enabled) repository.initialize()
        repository.mutate { it.copy(enabled = enabled) }
        storageBlocked = false; network.allowed = enabled; refresh(); resumeIfEligible()
    }
    fun acceptCode(raw: String) = command { reviewCode(raw) }
    suspend fun acceptPairingInput(raw: String) = executeCommand { reviewCode(raw) }
    private suspend fun reviewCode(raw: String) {
        val parsed = PairCode.parse(raw)
        demand(!Endpoint.parse(parsed.endpoint).address().isLoopbackAddress, "ENDPOINT_NOT_LOCAL")
        demand(repository.read() != null, "IDENTITY_NOT_READY")
        demand(network.foreground, "NETWORK_DISABLED")
        expiryJob?.cancel(); ticket = parsed
        mutable.update { it.copy(preview = InvitationPreview(parsed.name, parsed.fingerprint, parsed.endpoint, parsed.expiryMillis()), error = null) }
        expiryJob = scope.launch {
            delay((parsed.expiryMillis() - System.currentTimeMillis()).coerceIn(1, 150_000))
            commands.withLock { if (ticket === parsed) { ticket = null; mutable.update { it.copy(preview = null, error = "INVITATION_EXPIRED") } } }
        }
    }
    fun discardCode() = command { ticket = null; expiryJob?.cancel(); mutable.update { it.copy(preview = null) } }
    fun requestPairing(hostVerified: Boolean) = command {
        demand(hostVerified, "LOCAL_CONFIRMATION_REQUIRED")
        requireNetwork()
        val code = ticket ?: fail("INVALID_PAIRING_CODE")
        ticket = null; expiryJob?.cancel(); mutable.update { it.copy(preview = null) }
        invalidate("Requesting pairing"); networkJob?.cancelAndJoin()
        val turn = generation.get()
        updateFor(turn) { it.copy(pairingInProgress = true) }
        networkJob = scope.launch {
            try {
                val peer = engine.begin(code, settings.current().deviceName)
                if (turn == generation.get()) { refresh(); awaitOwner(peer.id, turn) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { if (turn == generation.get()) { refreshSafely(); showFailure(failure) } }
            finally { updateFor(turn) { it.copy(pairingInProgress = false) } }
        }
    }
    private suspend fun awaitOwner(id: String, turn: Long) {
        val peer = repository.read()?.peer(id) ?: return
        val remaining = (peer.expiresAt - System.currentTimeMillis()).coerceIn(1, 150_000)
        try {
            withTimeout(remaining) {
                while (isCurrent(turn)) {
                    val result = engine.prove(id); refresh()
                    if (!isCurrent(turn)) return@withTimeout
                    if (result.status == TrustStatus.APPROVED) { mutable.update { it.copy(status = "Approved. Review permissions and finish on this phone.") }; return@withTimeout }
                    mutable.update { it.copy(status = "Checking automatically · approve on your computer") }; delay(2000)
                }
            }
        } catch (_: TimeoutCancellationException) { if (isCurrent(turn)) { mutable.update { it.copy(error = "INVITATION_EXPIRED", status = "Pairing expired; cancel this attempt before scanning a new code") } } }
    }
    fun finish(id: String, compared: Boolean) = command {
        requireNetwork(); invalidate("Confirming durable pairing"); networkJob?.cancelAndJoin()
        val turn = generation.get()
        updateFor(turn) { it.copy(pairingInProgress = true) }
        networkJob = scope.launch {
            try { engine.finish(id, compared); if (isCurrent(turn)) { refresh(); updateFor(turn) { it.copy(pairingInProgress = false) }; maintain(id, turn) } }
            catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { if (turn == generation.get()) { refreshSafely(); showFailure(failure) } }
            finally { updateFor(turn) { it.copy(pairingInProgress = false) } }
        }
    }
    fun recover(id: String) = command {
        requireNetwork(); invalidate("Recovering acknowledgement"); networkJob?.cancelAndJoin()
        val turn = generation.get()
        updateFor(turn) { it.copy(pairingInProgress = true) }
        networkJob = scope.launch {
            try {
                val peer = repository.read()?.peer(id) ?: fail("PEER_NOT_FOUND")
                if (peer.status == TrustStatus.ACK_PENDING) { engine.recoverAcknowledgement(id); if (isCurrent(turn)) { refresh(); updateFor(turn) { it.copy(pairingInProgress = false) }; maintain(id, turn) } }
                else if (peer.status in setOf(TrustStatus.WAITING, TrustStatus.APPROVED)) awaitOwner(id, turn)
                else fail("PAIRING_STATE_CHANGED")
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) { if (turn == generation.get()) { refreshSafely(); showFailure(failure) } }
            finally { updateFor(turn) { it.copy(pairingInProgress = false) } }
        }
    }
    fun reconnect(id: String, endpoint: String) = command {
        requireNetwork(); invalidate("Connecting to saved identity"); networkJob?.cancelAndJoin()
        demand(!Endpoint.parse(endpoint).address().isLoopbackAddress, "ENDPOINT_NOT_LOCAL")
        engine.setEndpoint(id, endpoint); refresh()
        val turn = generation.get(); networkJob = scope.launch { maintain(id, turn) }
    }
    fun revoke(id: String) = command {
        // Persist the fence first; late health/ACK cannot restore revoked trust.
        try { engine.revoke(id) } finally { invalidate("Closing this device connection"); networkJob?.cancelAndJoin(); networkJob = null }
        refresh(); mutable.update { it.copy(status = "Device locally revoked") }
    }
    fun inputUnavailable() = command { fail("QR_UNAVAILABLE") }
    fun scanNetwork() = command { requireNetwork(); discovery.start() }
    fun stopDiscovery() { discovery.stop() }
    /** Serialize foreground file work with UI commands; pause and close the health lease first.
     * The IO-scoped socket never escapes to a ViewModel and no second production connection owner exists. */
    suspend fun <T> withFiles(use: suspend (PairingEngine, TrustedPeer, PeerSession, () -> Unit) -> T): T = contentAdmission.admit { withContent(null, "files", use) }
    suspend fun <T> withTransfer(peerId: String, use: suspend (PairingEngine, TrustedPeer, PeerSession, () -> Unit) -> T): T = withContent(peerId, "transfer", use)
    suspend fun <T> withAttachments(peerId: String, use: suspend (PairingEngine, TrustedPeer, PeerSession, () -> Unit) -> T): T = withContent(peerId, "attachments", use)
    suspend fun <T> withSync(peerId: String, use: suspend (PairingEngine, TrustedPeer, PeerSession, () -> Unit) -> T): T = withContent(peerId, "sync", use)
    suspend fun <T> withChat(peerId: String, use: suspend (PairingEngine, TrustedPeer, PeerSession, () -> Unit) -> T): T = withContent(peerId, "chat", use)
    private suspend fun <T> withContent(peerId: String?, kind: String, use: suspend (PairingEngine, TrustedPeer, PeerSession, () -> Unit) -> T): T = commands.withLock {
        requireNetwork()
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY")
        demand(doc.peers.all { it.status in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }, "PAIRING_IN_PROGRESS")
        val id = peerId ?: doc.preferredPeer ?: fail("PEER_NOT_FOUND")
        networkJob?.cancelAndJoin(); networkJob = null
        val turn = generation.get()
        contentLease = kind
        mutable.update { it.copy(busy = true, connectedPeer = null, status = "Authorizing foreground $kind request") }
        try {
            engine.withActiveSession(id) { peer, session ->
                use(engine, peer, session) {
                    demand(isCurrent(turn) && network.available() && !storageBlocked, "FILE_ACCESS_CHANGED")
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // A peer's file-storage error is FILE_REMOTE_STORAGE, never our local storage latch.
            if (errorCode(failure) in setOf("IDENTITY_KEY_MISSING", "IDENTITY_CHANGED", "IDENTITY_ANCHOR_MISSING", "TRUST_STORE_MISSING", "TRUST_STORE_INVALID", "KEYSTORE_UNAVAILABLE", "STORAGE_UNAVAILABLE", "CRYPTO_STATE_INVALID", "CHAT_STATE_INVALID", "ATTACHMENT_STATE_INVALID", "PIN_STATE_INVALID")) showFailure(failure)
            throw failure
        } finally {
            withContext(NonCancellable) {
                connector.closeAll(); contentLease = null
                refreshSafely(); mutable.update { it.copy(busy = false) }
                if (isCurrent(turn)) resumeIfEligible()
            }
        }
    }
    fun cancelFiles() { if (contentLease == "files") connector.closeAll() }
    fun cancelTransfers() { if (contentLease == "transfer") connector.closeAll() }
    fun cancelAttachments() { if (contentLease == "attachments") connector.closeAll() }
    fun cancelSync() { if (contentLease == "sync") connector.closeAll() }
    fun cancelChat() { if (contentLease == "chat") connector.closeAll() }
    suspend fun dismissFileOperation(operationId: String, acknowledged: Boolean) = commands.withLock {
        FilesClient(repository, engine).dismiss(operationId, acknowledged); refresh()
    }
    fun fileStorageFailure(failure: Exception) { showFailure(failure) }
    private fun requireNetwork() {
        demand(network.foreground && network.allowed, "NETWORK_DISABLED")
        demand(network.available(), "LOCAL_NETWORK_UNAVAILABLE")
    }
    private suspend fun resumeIfEligible() {
        val doc = mutable.value.document ?: return
        if (storageBlocked || !network.foreground || !doc.enabled || !network.available() || networkJob?.isActive == true) return
        // Pending ACKs/claims need an explicit recovery/cancel action, never an automatic re-pair.
        val id = doc.preferredPeer ?: return
        val peer = doc.peer(id)
        if (peer.status != TrustStatus.ACTIVE || peer.blocked || peer.failures >= 6) return
        val turn = generation.get(); networkJob = scope.launch { maintain(id, turn) }
    }
    private fun isCurrent(turn: Long) = turn == generation.get() && network.foreground && network.allowed
    private fun updateFor(turn: Long, change: (ConnectionsUiState) -> ConnectionsUiState) {
        mutable.update { current -> if (isCurrent(turn)) change(current) else current }
    }
    private suspend fun maintain(id: String, turn: Long) {
        while (isCurrent(turn)) {
            try {
                engine.withActiveSession(id) { peer, session ->
                    val started = android.os.SystemClock.elapsedRealtime()
                    while (isCurrent(turn) && android.os.SystemClock.elapsedRealtime() - started < 50_000) {
                        // Never display connected while an old health response is being retried.
                        updateFor(turn) { it.copy(connectedPeer = null, status = "Checking authenticated health") }
                        engine.health(peer, session)
                        if (!isCurrent(turn)) return@withActiveSession
                        refresh(); updateFor(turn) { it.copy(connectedPeer = id, status = "Connected · authenticated health", error = null) }
                        delay(5000)
                    }
                }
                updateFor(turn) { it.copy(connectedPeer = null, status = "Renewing authenticated session") }
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                if (!isCurrent(turn)) return
                val code = errorCode(failure)
                updateFor(turn) { it.copy(connectedPeer = null, error = code) }
                try {
                    val doc = repository.mutate { old ->
                        val peer = old.peer(id); demand(peer.status == TrustStatus.ACTIVE, "DEVICE_REVOKED")
                        old.replace(peer.copy(failures = (peer.failures + 1).coerceAtMost(6), blocked = !transient(code)))
                    }
                    refresh()
                    val peer = doc.peer(id)
                    if (peer.blocked || peer.failures >= 6) { updateFor(turn) { it.copy(status = "Stopped. Review the device, then retry manually.") }; return }
                    val delay = retryDelay(peer.failures, java.security.SecureRandom().nextInt(251))
                    updateFor(turn) { it.copy(status = "Disconnected · retry ${peer.failures}/6 in ${(delay + 999) / 1000} s") }
                    delay(delay)
                } catch (cancelled: CancellationException) { throw cancelled } catch (storage: Exception) { showFailure(storage); return }
            }
        }
    }
    private suspend fun refresh() {
        val doc = repository.read()
        val protection = if (doc != null && mutable.value.protection == null) repository.identity().protection else mutable.value.protection
        network.allowed = doc?.enabled == true && !storageBlocked
        mutable.update { it.copy(loading = false, document = doc?.copy(chat = null, attachments = emptyList(), syncCaches = emptyList(), pins = emptyList()), protection = protection, foreground = network.foreground, networkAvailable = network.available(), accessEpoch = generation.get(), transportAllowed = network.allowed) }
    }
    private suspend fun refreshSafely() { try { refresh() } catch (failure: Exception) { showFailure(failure) } }
    private fun showFailure(failure: Exception) {
        if (failure is CancellationException) return
        if (errorCode(failure) in setOf("IDENTITY_KEY_MISSING", "IDENTITY_CHANGED", "IDENTITY_ANCHOR_MISSING", "TRUST_STORE_MISSING", "TRUST_STORE_INVALID", "KEYSTORE_UNAVAILABLE", "STORAGE_UNAVAILABLE", "CRYPTO_STATE_INVALID", "CHAT_STATE_INVALID", "ATTACHMENT_STATE_INVALID", "PIN_STATE_INVALID")) { storageBlocked = true; network.allowed = false; discovery.stop(); generation.incrementAndGet(); networkJob?.cancel() }
        connector.closeAll()
        mutable.update { it.copy(loading = false, connectedPeer = null, error = errorCode(failure), accessEpoch = generation.get(), transportAllowed = network.allowed, status = "Needs attention. No identity reset or insecure fallback was attempted.", pairingInProgress = false) }
    }
    private fun errorCode(failure: Exception) = (failure as? ConnectionFailure)?.code ?: "STORAGE_UNAVAILABLE"
}

fun connectionMessage(code: String): String = when (code) {
    "IDENTITY_NOT_READY" -> "Prepare this phone’s protected identity first."
    "NETWORK_DISABLED" -> "Enable local connections and keep NARADA visible."
    "LOCAL_NETWORK_UNAVAILABLE" -> "Use a private IPv4 Wi-Fi or Ethernet network. Cellular, VPN and public-only routes are not used."
    "ENDPOINT_NOT_LOCAL", "INVALID_ENDPOINT" -> "Use a private IPv4 address and port, without a URL path, credentials or DNS name. Phone loopback is not a computer address."
    "INVITATION_EXPIRED", "CLOCK_SKEW" -> "Check both device clocks and ask the desktop for a fresh two-minute invitation."
    "INVALID_PAIRING_CODE", "INVALID_HANDSHAKE", "DUPLICATE_FIELD", "BODY_TOO_LARGE" -> "This code or response does not match the supported bounded protocol."
    "OWNER_APPROVAL_REQUIRED", "LOCAL_CONFIRMATION_REQUIRED" -> "Compare the full fingerprints and approve on the desktop before finishing on this phone."
    "ALREADY_PAIRED", "PAIRING_IN_PROGRESS" -> "An existing pairing or unfinished attempt must be reviewed before starting another."
    "PEER_TIMEOUT", "PEER_UNREACHABLE", "CONNECTION_INTERRUPTED" -> "The saved endpoint did not respond. Check Wi-Fi, desktop listener, local-network restrictions and firewall; no pin was changed."
    "IDENTITY_REJECTED", "PEER_AUTH_UNCONFIRMED", "REMOTE_STATE_ROLLBACK" -> "The saved identity or authorization state could not be confirmed. Check both devices; there is no automatic re-pairing or downgrade."
    "IDENTITY_KEY_MISSING", "IDENTITY_CHANGED", "IDENTITY_ANCHOR_MISSING", "TRUST_STORE_MISSING", "TRUST_STORE_INVALID", "KEYSTORE_UNAVAILABLE", "STORAGE_UNAVAILABLE", "CRYPTO_STATE_INVALID", "CHAT_STATE_INVALID", "ATTACHMENT_STATE_INVALID", "PIN_STATE_INVALID" -> "Protected connection state is missing, unavailable or damaged. Original data was preserved. Do not clear data or silently create replacement keys."
    "QR_UNAVAILABLE" -> "No supported QR code was decoded. Try a clear image or paste the desktop’s code explicitly."
    "DEVICE_REVOKED", "PAIRING_STATE_CHANGED" -> "Trust changed or was revoked. A late response cannot reactivate it. Review both devices."
    "PEER_LIMIT" -> "This development store retains up to 16 device records, including revocation history."
    else -> "The authenticated request was rejected. Review the desktop and use a new explicit action; remote error text is not displayed."
}
