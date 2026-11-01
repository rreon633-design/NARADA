package com.narada.connection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

class AttachmentResult<T>(val value: T, val scope: ChatScope) { override fun toString() = "AttachmentResult([private result])" }
interface AttachmentsGateway {
    suspend fun capabilities(record: AttachmentRecord): AttachmentResult<UploadCapabilities>
    suspend fun begin(record: AttachmentRecord): AttachmentResult<UploadStatus>
    suspend fun status(record: AttachmentRecord): AttachmentResult<UploadStatus>
    suspend fun upload(record: AttachmentRecord, index: Int, bytes: ByteArray): AttachmentResult<Unit>
    suspend fun finish(record: AttachmentRecord): AttachmentResult<UploadStatus>
    suspend fun download(record: AttachmentRecord, index: Int): AttachmentResult<ByteArray>
    fun cancel()
}
class AttachmentsClient(private val repository: TrustRepository, private val pairing: PairingEngine) {
    private suspend fun fence(peer: TrustedPeer, r: AttachmentRecord, guard: () -> Unit) {
        currentCoroutineContext().ensureActive(); guard(); r.validate()
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); ChatScope(peer).check(doc)
        AttachmentRules.authority(doc, r.peerId, r.pairingAttempt)
        demand(peer.id == r.peerId && doc.attachment(r.id) == r, "ATTACHMENT_STOPPED")
        demand(r.state == AttachmentState.RUNNING || r.state == AttachmentState.PICKER_PENDING, "ATTACHMENT_STOPPED")
        if (r.state == AttachmentState.RUNNING) {
            val m = doc.chat?.message(r.messageId) ?: fail("MESSAGE_UNAVAILABLE")
            demand(m.peerId == r.peerId && m.pairingAttempt == r.pairingAttempt && m.direction == r.direction && m.attachment == r.secret && m.status == ChatStatus.DELIVERED, "ATTACHMENT_MESSAGE_PENDING")
        }
    }
    private suspend fun before(p: TrustedPeer, s: PeerSession, r: AttachmentRecord, g: () -> Unit): TrustedPeer {
        fence(p, r, g); val fresh = pairing.health(p, s); fence(fresh, r, g); return fresh
    }
    private suspend fun after(p: TrustedPeer, s: PeerSession, r: AttachmentRecord, g: () -> Unit) {
        fence(p, r, g); pairing.health(p, s); fence(p, r, g)
    }
    suspend fun capabilities(peer: TrustedPeer, session: PeerSession, r: AttachmentRecord, guard: () -> Unit = {}): AttachmentResult<UploadCapabilities> {
        demand(r.direction == ChatDirection.OUTGOING, "INVALID_ATTACHMENT")
        val p = before(peer, session, r, guard)
        val value: UploadCapabilities = session.post(AttachmentRules.PATH, UploadWire(action = buildJsonObject { put("kind", "capabilities") }))
        value.validate(); after(p, session, r, guard); return AttachmentResult(value, ChatScope(p))
    }
    suspend fun control(kind: String, peer: TrustedPeer, session: PeerSession, r: AttachmentRecord, guard: () -> Unit = {}): AttachmentResult<UploadStatus> {
        demand(kind in setOf("begin", "status", "finish") && r.direction == ChatDirection.OUTGOING && r.receipt != null && r.state == AttachmentState.RUNNING, "ATTACHMENT_STOPPED")
        val p = before(peer, session, r, guard)
        val value: UploadStatus = session.post(AttachmentRules.PATH, UploadWire(action = buildJsonObject {
            put("kind", kind); put("message_id", r.messageId); put("blob_id", r.secret?.descriptor?.blob_id ?: fail("INVALID_ATTACHMENT"))
        }))
        value.validate(r); if (kind == "finish") demand(value.state == "available" && value.missing.isEmpty(), "ATTACHMENT_NOT_VERIFIED")
        after(p, session, r, guard); return AttachmentResult(value, ChatScope(p))
    }
    suspend fun upload(peer: TrustedPeer, session: PeerSession, r: AttachmentRecord, index: Int, bytes: ByteArray, guard: () -> Unit = {}): AttachmentResult<Unit> {
        demand(r.direction == ChatDirection.OUTGOING && r.state == AttachmentState.RUNNING && r.receipt != null, "ATTACHMENT_STOPPED")
        val d = r.secret?.descriptor ?: fail("INVALID_ATTACHMENT")
        demand(bytes.size == d.length(index) && sha256(bytes) == d.hashes[index], "HASH_MISMATCH")
        val p = before(peer, session, r, guard)
        val raw = session.putAttachmentChunk(r.messageId, d.blob_id, index, bytes)
        val reply = try { Wire.decode<Envelope<UploadChunkStored>>(raw) } finally { raw.fill(0) }
        demand(reply.ok == (reply.data != null) && reply.ok == (reply.error == null), "INVALID_ATTACHMENT_RESPONSE")
        reply.error?.let { attachmentRemoteFailure(it.code) }
        demand(reply.data == UploadChunkStored(1, r.messageId, d.blob_id, index, true), "INVALID_ATTACHMENT_RESPONSE")
        after(p, session, r, guard); return AttachmentResult(Unit, ChatScope(p))
    }
    suspend fun download(peer: TrustedPeer, session: PeerSession, r: AttachmentRecord, index: Int, guard: () -> Unit = {}): AttachmentResult<ByteArray> {
        demand(r.direction == ChatDirection.INCOMING && r.state == AttachmentState.RUNNING, "ATTACHMENT_STOPPED")
        val d = r.secret?.descriptor ?: fail("INVALID_ATTACHMENT"); val p = before(peer, session, r, guard)
        val bytes = session.getBlobChunk(d.blob_id, index, d.length(index))
        try { demand(bytes.size == d.length(index) && sha256(bytes) == d.hashes[index], "HASH_MISMATCH"); after(p, session, r, guard); return AttachmentResult(bytes, ChatScope(p)) }
        catch (e: Exception) { bytes.fill(0); throw e }
    }
}
class PacedAttachmentsGateway(private val delegate: AttachmentsGateway, private val admission: ContentAdmission) : AttachmentsGateway {
    override suspend fun capabilities(record: AttachmentRecord) = admission.admit { delegate.capabilities(record) }
    override suspend fun begin(record: AttachmentRecord) = admission.admit { delegate.begin(record) }
    override suspend fun status(record: AttachmentRecord) = admission.admit { delegate.status(record) }
    override suspend fun upload(record: AttachmentRecord, index: Int, bytes: ByteArray) = admission.admit { delegate.upload(record, index, bytes) }
    override suspend fun finish(record: AttachmentRecord) = admission.admit { delegate.finish(record) }
    override suspend fun download(record: AttachmentRecord, index: Int) = admission.admit { delegate.download(record, index) }
    override fun cancel() = delegate.cancel()
}
fun attachmentRemoteFailure(code: String): Nothing = fail(when (code) {
    "NO_PEER_ROUTE", "PEER_INITIATES" -> "ATTACHMENT_UPLOAD_UNSUPPORTED"
    "STORAGE_UNAVAILABLE", "CONTENT_STORE_LOCKED", "CONTENT_STORE_MISSING", "CONTENT_IDENTITY_CHANGED", "CRYPTO_STATE_INVALID" -> "ATTACHMENT_REMOTE_STORAGE"
    "STORAGE_FULL" -> "ATTACHMENT_REMOTE_FULL"
    in setOf("ACCESS_DENIED", "DEVICE_REVOKED", "PERMISSION_DENIED", "RATE_LIMITED", "IDENTITY_REJECTED", "NETWORK_DISABLED", "PROTOCOL_INCOMPATIBLE", "CAPABILITIES_INCOMPATIBLE", "CONTENT_QUOTA", "MESSAGE_UNAVAILABLE", "SESSION_INVALIDATED", "BLOB_EXPIRED", "BLOB_PAUSED", "BLOB_INCOMPLETE", "BLOB_ID_CONFLICT", "INVALID_ATTACHMENT", "INVALID_CHUNK", "HASH_MISMATCH", "STAGING_CHANGED", "ATTACHMENT_DECRYPT_FAILED") -> code
    else -> "PEER_REJECTED"
})
