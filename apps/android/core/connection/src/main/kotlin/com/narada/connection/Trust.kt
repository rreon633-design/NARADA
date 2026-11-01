package com.narada.connection

import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable enum class TrustStatus { CLAIMING, WAITING, APPROVED, ACK_PENDING, ACTIVE, REVOKED }
@Serializable data class TrustedPeer(
    val id: String, val fingerprint: String, val certificate: String = "", val name: String, val platform: String = "windows",
    val endpoint: String, val invitationId: String, val attemptId: String, val expiresAt: Long,
    val status: TrustStatus, val localEpoch: Long = 1, val remotePermissions: Permissions = Permissions(),
    val remoteRevision: Long = 0, val remoteEpoch: Long = 0, val remoteOperations: List<String> = emptyList(),
    val failures: Int = 0, val blocked: Boolean = false,
)
@Serializable data class TrustDocument(
    val version: Int = 6, val storeId: String, val deviceId: String, val fingerprint: String, val certificate: String,
    val generation: Long = 1, val enabled: Boolean = false, val preferredPeer: String? = null,
    val peers: List<TrustedPeer> = emptyList(), val fileOperations: List<FileOperation> = emptyList(),
    val downloads: List<DownloadRecord> = emptyList(), val chat: ChatState? = null,
    val attachments: List<AttachmentRecord> = emptyList(), val syncCaches: List<SyncCache> = emptyList(), val pins: List<OfflinePin> = emptyList(),
) {
    fun validate() {
        demand(version in 1..6 && generation > 0 && (version != 1 || fileOperations.isEmpty()) && (version >= 3 || downloads.isEmpty()) && (version >= 4 || chat == null) && (version >= 5 || attachments.isEmpty()) && (version >= 6 || syncCaches.isEmpty() && pins.isEmpty() && downloads.none { it.syncRequired }), "TRUST_STORE_INVALID"); canonicalUuid(storeId); validHash(deviceId); validHash(fingerprint)
        val local = Certificates.parse(decodeCertificate(certificate))
        demand(Certificates.deviceId(local) == deviceId && sha256(local.encoded) == fingerprint, "IDENTITY_CHANGED")
        demand(peers.size <= 16 && peers.map { it.id }.distinct().size == peers.size, "TRUST_STORE_INVALID")
        demand(peers.count { it.status !in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) } <= 1, "TRUST_STORE_INVALID")
        demand(preferredPeer == null || peers.any { it.id == preferredPeer && it.status == TrustStatus.ACTIVE }, "TRUST_STORE_INVALID")
        demand(fileOperations.size <= 32 && fileOperations.map { it.request.operationId }.distinct().size == fileOperations.size, "TRUST_STORE_INVALID")
        fileOperations.forEach { it.validate(); demand(peers.any { peer -> peer.id == it.peerId }, "TRUST_STORE_INVALID") }
        demand(downloads.size <= TransferRules.MAX_JOBS && downloads.map { it.id }.distinct().size == downloads.size && downloads.sumOf { it.size } <= TransferRules.QUOTA, "TRUST_STORE_INVALID")
        downloads.forEach { it.validate(); demand(peers.any { peer -> peer.id == it.peerId }, "TRUST_STORE_INVALID") }
        chat?.validate(deviceId, peers)
        demand(attachments.size <= AttachmentRules.MAX_JOBS && attachments.map { it.id }.distinct().size == attachments.size && attachments.map { it.messageId }.distinct().size == attachments.size && attachments.sumOf { it.reservedBytes } <= AttachmentRules.QUOTA && attachments.sumOf { it.reservedBytes } + downloads.sumOf { it.size } <= TransferRules.QUOTA, "TRUST_STORE_INVALID")
        demand(attachments.none { a -> downloads.any { it.id == a.id } }, "TRUST_STORE_INVALID")
        attachments.forEach { r ->
            r.validate(); peer(r.peerId)
            val m = chat?.messages?.find { it.id == r.messageId }
            if (r.direction == ChatDirection.INCOMING || r.receipt != null) demand(m != null, "ATTACHMENT_STATE_INVALID")
            if (m != null) demand(m.attachment == r.secret && m.peerId == r.peerId && m.pairingAttempt == r.pairingAttempt && m.direction == r.direction, "ATTACHMENT_STATE_INVALID")
        }
        validateOffline()
        peers.forEach { peer ->
            validHash(peer.id); validHash(peer.fingerprint); validName(peer.name); validPlatform(peer.platform); Endpoint.parse(peer.endpoint)
            demand(peer.remoteOperations.isEmpty() || runCatching { Health(Hello(), peer.id, peer.remotePermissions, peer.remoteRevision, peer.remoteEpoch, peer.remoteOperations).validate(peer.id) }.isSuccess, "TRUST_STORE_INVALID")
            canonicalUuid(peer.invitationId); canonicalUuid(peer.attemptId); peer.remotePermissions.validate()
            demand(peer.id != deviceId && peer.localEpoch > 0 && peer.failures in 0..6 && peer.expiresAt > 0 && peer.remoteRevision in 0..0xffffffffL && peer.remoteEpoch in 0..0xffffffffL, "TRUST_STORE_INVALID")
            if (peer.certificate.isNotEmpty()) {
                // Tombstones and other peers remain readable when an old remote certificate expires.
                // Every actual TLS handshake still checks CURRENT certificate validity.
                val cert = Certificates.parse(decodeCertificate(peer.certificate), server = true, now = null)
                demand(sha256(cert.encoded) == peer.fingerprint && Certificates.deviceId(cert) == peer.id, "TRUST_STORE_INVALID")
            } else demand(peer.status in setOf(TrustStatus.CLAIMING, TrustStatus.REVOKED), "TRUST_STORE_INVALID")
            if (peer.status == TrustStatus.REVOKED) demand(peer.remotePermissions == Permissions(), "TRUST_STORE_INVALID")
        }
    }
    override fun toString() = "TrustDocument([protected state redacted])"
    fun peer(id: String): TrustedPeer = peers.singleOrNull { it.id == id } ?: fail("PEER_NOT_FOUND")
    fun replace(peer: TrustedPeer): TrustDocument = copy(peers = peers.filterNot { it.id == peer.id } + peer)
}
fun decodeCertificate(encoded: String): ByteArray {
    demand(encoded.length <= 5500, "INVALID_CERTIFICATE")
    return try { Base64.getDecoder().decode(encoded).also { demand(it.size <= 4096, "INVALID_CERTIFICATE") } } catch (_: IllegalArgumentException) { fail("INVALID_CERTIFICATE") }
}
interface TrustRepository {
    suspend fun read(): TrustDocument?
    suspend fun initialize(): TrustDocument
    suspend fun identity(): TransportIdentity
    /** Must validate and atomically persist before returning; no in-memory success on write failure. */
    suspend fun mutate(change: (TrustDocument) -> TrustDocument): TrustDocument
}

class PairingEngine(private val repository: TrustRepository, private val connector: PeerConnector, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun begin(code: PairCode, localName: String): TrustedPeer = withContext(Dispatchers.IO) {
        code.validate(now()); validName(localName)
        val local = repository.identity(); demand(code.device_id != local.deviceId, "SELF_PAIRING")
        val attempt = UUID.randomUUID().toString()
        val pending = repository.mutate { doc ->
            demand(doc.enabled, "NETWORK_DISABLED")
            demand(doc.peers.none { it.status !in setOf(TrustStatus.ACTIVE, TrustStatus.REVOKED) }, "PAIRING_IN_PROGRESS")
            val previous = doc.peers.find { it.id == code.device_id }
            demand(previous == null || previous.status == TrustStatus.REVOKED, "ALREADY_PAIRED")
            demand(previous != null || doc.peers.size < 16, "PEER_LIMIT")
            doc.replace(TrustedPeer(code.device_id, code.fingerprint, name = code.name, endpoint = Endpoint.parse(code.endpoint).authority, invitationId = code.invitation_id, attemptId = attempt, expiresAt = code.expiryMillis(), status = TrustStatus.CLAIMING, localEpoch = (previous?.localEpoch ?: 0) + 1))
        }.peer(code.device_id)
        val reply = connector.connect(Endpoint.parse(code.endpoint), PeerPin(code.fingerprint, code.device_id), null).use { session ->
            val current = repository.read() ?: fail("IDENTITY_NOT_READY")
            owned(current, pending, setOf(TrustStatus.CLAIMING)); demand(current.enabled, "NETWORK_DISABLED")
            // The connector proves the exact server pin and TLS identity BEFORE these secret bytes are written.
            val result: ClaimReply = session.post("/pair/v1/claim", Claim(invitation_id = code.invitation_id, attempt_id = attempt, token = code.token, certificate = Base64.getEncoder().encodeToString(local.certificate.encoded), name = localName))
            result.hello.validate(); demand(result.attempt_id == attempt && result.api_port in 1..65535)
            validName(result.name); validPlatform(result.platform)
            val cert = decodeCertificate(result.certificate); Certificates.parse(cert, server = true)
            demand(cert.contentEquals(session.certificate) && sha256(cert) == code.fingerprint && Certificates.deviceId(Certificates.parse(cert, true)) == code.device_id, "IDENTITY_REJECTED")
            result
        }
        repository.mutate { doc ->
            val current = owned(doc, pending, setOf(TrustStatus.CLAIMING))
            demand(doc.enabled, "NETWORK_DISABLED")
            doc.replace(current.copy(certificate = reply.certificate, endpoint = Endpoint(Endpoint.parse(code.endpoint).host, reply.api_port).authority, name = reply.name, platform = reply.platform, status = TrustStatus.WAITING, localEpoch = current.localEpoch + 1))
        }.peer(pending.id)
    }
    suspend fun prove(id: String): TrustedPeer = withContext(Dispatchers.IO) {
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); demand(doc.enabled, "NETWORK_DISABLED")
        val peer = doc.peer(id); demand(peer.status in setOf(TrustStatus.WAITING, TrustStatus.APPROVED), "PAIRING_STATE_CHANGED")
        demand(now() < peer.expiresAt, "INVITATION_EXPIRED")
        val reply = connect(peer).use { it.post<Proof, PairState>("/pair/v1/prove", Proof(invitation_id = peer.invitationId, attempt_id = peer.attemptId)) }
        reply.hello.validate(); reply.permissions.validate(); demand(reply.state in setOf("awaiting_owner", "approved"))
        if (reply.state == "awaiting_owner") demand(reply.permissions == Permissions(), "INVALID_GRANTS")
        repository.mutate { current ->
            val old = owned(current, peer, setOf(TrustStatus.WAITING, TrustStatus.APPROVED)); demand(current.enabled, "NETWORK_DISABLED")
            demand(old.status != TrustStatus.APPROVED || reply.state == "approved", "PAIRING_STATE_CHANGED")
            val stage = if (reply.state == "approved") TrustStatus.APPROVED else TrustStatus.WAITING
            if (old.status == stage && old.remotePermissions == reply.permissions) current
            else current.replace(old.copy(status = stage, remotePermissions = reply.permissions, localEpoch = old.localEpoch + 1))
        }.peer(id)
    }
    suspend fun finish(id: String, fingerprintsCompared: Boolean): TrustedPeer = withContext(Dispatchers.IO) {
        demand(fingerprintsCompared, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { doc ->
            val peer = doc.peer(id); demand(doc.enabled, "NETWORK_DISABLED")
            demand(peer.status == TrustStatus.APPROVED && now() < peer.expiresAt, "PAIRING_STATE_CHANGED")
            // This durable state is written BEFORE the final ACK. Recovery never replays a token.
            doc.replace(peer.copy(status = TrustStatus.ACK_PENDING, localEpoch = peer.localEpoch + 1))
        }
        recoverAcknowledgement(id)
    }
    suspend fun recoverAcknowledgement(id: String): TrustedPeer = withContext(Dispatchers.IO) {
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); demand(doc.enabled, "NETWORK_DISABLED")
        val peer = doc.peer(id); demand(peer.status == TrustStatus.ACK_PENDING, "PAIRING_STATE_CHANGED")
        // An expired invitation can still have an idempotent ACTIVE ACK after a lost reply.
        // Only the pinned desktop decides; no local timeout invents activation.
        val reply = connect(peer).use { it.post<Proof, PairState>("/pair/v1/ack", Proof(invitation_id = peer.invitationId, attempt_id = peer.attemptId)) }
        reply.hello.validate(); reply.permissions.validate(); demand(reply.state == "active", "OWNER_APPROVAL_REQUIRED")
        repository.mutate { current ->
            val old = owned(current, peer, setOf(TrustStatus.ACK_PENDING)); demand(current.enabled, "NETWORK_DISABLED")
            current.replace(old.copy(status = TrustStatus.ACTIVE, remotePermissions = reply.permissions, localEpoch = old.localEpoch + 1)).copy(preferredPeer = id)
        }.peer(id)
    }
    /** Production callers keep the socket INSIDE this IO scope: cancellation cannot discard
     * an acquired socket while dispatching a Closeable result back to the UI dispatcher. */
    suspend fun <T> withActiveSession(id: String, use: suspend (TrustedPeer, PeerSession) -> T): T = withContext(Dispatchers.IO) {
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); demand(doc.enabled, "NETWORK_DISABLED")
        val peer = doc.peer(id); demand(peer.status == TrustStatus.ACTIVE && !peer.blocked && peer.failures < 6, "DEVICE_REVOKED")
        connect(peer).use { use(peer, it) }
    }
    // Low-level lease for same-module transport tests. Android uses withActiveSession.
    internal suspend fun openActive(id: String): Pair<TrustedPeer, PeerSession> = withContext(Dispatchers.IO) {
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); demand(doc.enabled, "NETWORK_DISABLED")
        val peer = doc.peer(id); demand(peer.status == TrustStatus.ACTIVE && !peer.blocked && peer.failures < 6, "DEVICE_REVOKED")
        peer to connect(peer)
    }
    suspend fun health(peer: TrustedPeer, session: PeerSession): TrustedPeer = withContext(Dispatchers.IO) {
        val current = repository.read() ?: fail("IDENTITY_NOT_READY")
        owned(current, peer, setOf(TrustStatus.ACTIVE)); demand(current.enabled, "NETWORK_DISABLED")
        val health: Health = session.post("/api/v1/health", Hello()); health.validate(peer.id)
        repository.mutate { doc ->
            val current = owned(doc, peer, setOf(TrustStatus.ACTIVE)); demand(doc.enabled, "NETWORK_DISABLED")
            demand(health.grant_revision >= current.remoteRevision && health.session_epoch >= current.remoteEpoch, "REMOTE_STATE_ROLLBACK")
            if (current.remoteRevision > 0 && current.remoteRevision == health.grant_revision && current.remoteEpoch == health.session_epoch)
                demand(current.remotePermissions == health.permissions, "INVALID_GRANTS")
            doc.replace(current.copy(remotePermissions = health.permissions, remoteRevision = health.grant_revision, remoteEpoch = health.session_epoch, remoteOperations = health.operations, failures = 0, blocked = false))
        }.peer(peer.id)
    }
    suspend fun revoke(id: String) {
        try {
            repository.mutate { doc ->
                val peer = doc.peer(id)
                doc.replace(peer.copy(status = TrustStatus.REVOKED, remotePermissions = Permissions(), remoteOperations = emptyList(), localEpoch = peer.localEpoch + 1, blocked = true)).copy(preferredPeer = doc.preferredPeer?.takeUnless { it == id })
            }
        } finally { connector.closeAll() } // Close even if persistence failed; never report success then.
    }
    suspend fun setEndpoint(id: String, address: String) {
        val endpoint = Endpoint.parse(address)
        try {
            repository.mutate { doc ->
                val peer = doc.peer(id); demand(peer.status == TrustStatus.ACTIVE, "DEVICE_REVOKED")
                doc.replace(peer.copy(endpoint = endpoint.authority, localEpoch = peer.localEpoch + 1, blocked = false, failures = 0)).copy(preferredPeer = id)
            }
        } finally { connector.closeAll() }
    }
    private suspend fun connect(peer: TrustedPeer): PeerSession = withContext(Dispatchers.IO) {
        val identity = repository.identity()
        val current = repository.read() ?: fail("IDENTITY_NOT_READY")
        owned(current, peer, setOf(peer.status)); demand(current.enabled, "NETWORK_DISABLED")
        currentCoroutineContext().ensureActive()
        val session = connector.connect(Endpoint.parse(peer.endpoint), PeerPin(peer.fingerprint, peer.id), identity)
        try {
            currentCoroutineContext().ensureActive()
            val after = repository.read() ?: fail("IDENTITY_NOT_READY")
            owned(after, peer, setOf(peer.status)); demand(after.enabled, "NETWORK_DISABLED")
            session
        } catch (failure: Exception) { session.close(); throw failure }
    }
    private fun owned(doc: TrustDocument, captured: TrustedPeer, states: Set<TrustStatus>): TrustedPeer {
        val current = doc.peer(captured.id)
        demand(current.attemptId == captured.attemptId && current.localEpoch == captured.localEpoch && current.status in states, "PAIRING_STATE_CHANGED")
        return current
    }
}
