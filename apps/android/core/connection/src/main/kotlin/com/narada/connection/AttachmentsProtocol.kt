package com.narada.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

object AttachmentRules {
    const val PATH = "/api/v1/attachment-upload"
    const val CAPABILITY = "attachments.age.v1"
    const val UPLOAD = "attachments.upload.v1"
    const val MAX_PLAIN = 8_388_608
    const val MAX_CIPHER = 8_454_144
    const val MAX_JOBS = 4
    const val QUOTA = 33_816_576L
    fun name(value: String) { FileRules.text(value, 180); demand(value.none { it == '/' || it == '\\' }, "INVALID_ATTACHMENT") }
    fun authority(doc: TrustDocument, peer: String, attempt: String): TrustedPeer = ChatRules.authority(doc, peer, attempt).also {
        demand(it.remotePermissions.attachment && CAPABILITY in it.remoteOperations, "ATTACHMENTS_UNSUPPORTED")
    }
    fun downloadPath(blob: String, index: Int): String { canonicalUuid(blob); demand(index in 0..32, "INVALID_CHUNK"); return "/api/v1/blobs/chunks/$blob/$index" }
    fun uploadPath(message: String, blob: String, index: Int): String { canonicalUuid(message); downloadPath(blob, index); return "$PATH/chunks/$message/$blob/$index" }
}
@Serializable data class BlobDescriptor(val version: Int = 1, val blob_id: String, val size: Long, val sha256: String, val hashes: List<String>) {
    fun validate() {
        canonicalUuid(blob_id); TransferRules.hash(sha256)
        demand(version == 1 && size in 1..AttachmentRules.MAX_CIPHER.toLong() && hashes.size in 1..33 && hashes.size.toLong() == (size + TransferRules.CHUNK - 1) / TransferRules.CHUNK, "INVALID_ATTACHMENT")
        hashes.forEach(TransferRules::hash)
    }
    fun length(index: Int): Int { validate(); demand(index in hashes.indices, "INVALID_CHUNK"); return minOf(TransferRules.CHUNK.toLong(), size - index.toLong() * TransferRules.CHUNK).toInt() }
    override fun toString() = "BlobDescriptor([protected descriptor])"
}
@Serializable data class AttachmentSecret(val descriptor: BlobDescriptor, val name: String, val plaintext_size: Long, val plaintext_sha256: String, val identity: String) {
    fun validate() {
        descriptor.validate(); AttachmentRules.name(name); TransferRules.hash(plaintext_sha256)
        demand(plaintext_size in 0..AttachmentRules.MAX_PLAIN.toLong() && identity.length in 20..128 && identity.matches(Regex("AGE-SECRET-KEY-1[0-9A-Z]+")), "INVALID_ATTACHMENT")
    }
    override fun toString() = "AttachmentSecret([key, name and protected manifest redacted])"
}
@Serializable data class UploadCapabilities(val version: Int, val operation: String, val crypto: String, val chunk_size: Int, val max_plaintext: Int, val max_ciphertext: Int, val max_chunks: Int) {
    fun validate() { demand(this == UploadCapabilities(1, AttachmentRules.UPLOAD, "age.x25519.v1", TransferRules.CHUNK, AttachmentRules.MAX_PLAIN, AttachmentRules.MAX_CIPHER, 33), "ATTACHMENT_UPLOAD_UNSUPPORTED") }
}
@Serializable data class UploadWire(val hello: Hello = Hello(), val version: Int = 1, val action: JsonObject)
@Serializable data class UploadStatus(val version: Int, val message_id: String, val blob_id: String, val ciphertext_sha256: String, val state: String, val missing: List<Int>) {
    fun validate(r: AttachmentRecord) {
        val d = r.secret?.descriptor ?: fail("INVALID_ATTACHMENT")
        demand(version == 1 && message_id == r.messageId && blob_id == d.blob_id && ciphertext_sha256 == d.sha256 && state in setOf("receiving", "paused", "available"), "INVALID_ATTACHMENT_RESPONSE")
        demand(missing == missing.distinct().sorted() && missing.all { it in d.hashes.indices } && (state != "available" || missing.isEmpty()), "INVALID_ATTACHMENT_RESPONSE")
    }
}
@Serializable data class UploadChunkStored(val version: Int, val message_id: String, val blob_id: String, val index: Int, val stored: Boolean)
@Serializable enum class AttachmentState { PICKER_PENDING, IMPORTING, STAGING, PAUSED, RUNNING, VERIFIED, CANCELLED, CORRUPT }
@Serializable data class AttachmentReceipt(val ciphertextSha256: String, val plaintextSha256: String, val plaintextSize: Long, val verifiedAt: Long)
@Serializable data class AttachmentRecord(
    val id: String, val messageId: String, val peerId: String, val pairingAttempt: String, val direction: ChatDirection,
    val createdAt: Long, val text: String = "", val state: AttachmentState, val secret: AttachmentSecret? = null,
    val sourceUri: String? = null, val runId: String? = null, val chunks: List<Int> = emptyList(),
    val receipt: AttachmentReceipt? = null, val remoteStored: Boolean = false, val uploaded: List<Int> = emptyList(),
    val uploadVersion: Int = 0, val error: String? = null, val exports: List<ExportRecord> = emptyList(),
) {
    val reservedBytes: Long get() = secret?.descriptor?.size ?: AttachmentRules.MAX_CIPHER.toLong()
    val available: Boolean get() = receipt != null && state in setOf(AttachmentState.VERIFIED, AttachmentState.PAUSED, AttachmentState.RUNNING)
    fun validate() {
        canonicalUuid(id); canonicalUuid(messageId); validHash(peerId); canonicalUuid(pairingAttempt); ChatRules.text(text, true)
        demand(uploadVersion in 0..1 && (direction != ChatDirection.INCOMING || uploadVersion == 0), "ATTACHMENT_STATE_INVALID")
        demand(createdAt > 0 && (state == AttachmentState.RUNNING) == (runId != null), "ATTACHMENT_STATE_INVALID"); runId?.let(::canonicalUuid)
        demand(error == null || error.matches(Regex("[A-Z][A-Z0-9_]{0,63}")), "ATTACHMENT_STATE_INVALID")
        sourceUri?.let(TransferRules::uri)
        demand(sourceUri == null || state == AttachmentState.IMPORTING, "ATTACHMENT_STATE_INVALID")
        val s = secret
        if (s == null) demand(direction == ChatDirection.OUTGOING && chunks.isEmpty() && uploaded.isEmpty() && receipt == null && state in setOf(AttachmentState.PICKER_PENDING, AttachmentState.IMPORTING, AttachmentState.CANCELLED, AttachmentState.PAUSED), "ATTACHMENT_STATE_INVALID")
        else { s.validate(); demand(chunks == chunks.distinct().sorted() && chunks.all { it in s.descriptor.hashes.indices }, "ATTACHMENT_STATE_INVALID")
            receipt?.let { demand(it.ciphertextSha256 == s.descriptor.sha256 && it.plaintextSha256 == s.plaintext_sha256 && it.plaintextSize == s.plaintext_size && it.verifiedAt >= createdAt && chunks.size == s.descriptor.hashes.size, "ATTACHMENT_STATE_INVALID") }
            demand(uploaded == uploaded.distinct().sorted() && uploaded.all { it in s.descriptor.hashes.indices }, "ATTACHMENT_STATE_INVALID")
        }
        demand(state != AttachmentState.VERIFIED || receipt != null, "ATTACHMENT_STATE_INVALID")
        demand(!remoteStored || direction == ChatDirection.OUTGOING && receipt != null && uploaded.size == s?.descriptor?.hashes?.size, "ATTACHMENT_STATE_INVALID")
        demand(direction != ChatDirection.INCOMING || !remoteStored && uploaded.isEmpty(), "ATTACHMENT_STATE_INVALID")
        demand(exports.size <= 2 && exports.map { it.id }.distinct().size == exports.size && (exports.isEmpty() || receipt != null), "ATTACHMENT_STATE_INVALID"); exports.forEach(ExportRecord::validate)
    }
    /** Storage-only adapter; it NEVER enters TransfersClient or any wire request. No age key/name is
     * put in ordinary transfer metadata. A separate directory and local UUID isolate these chunks. */
    fun ciphertextRecord(): DownloadRecord {
        val d = secret?.descriptor ?: fail("INVALID_ATTACHMENT")
        val draft = TransferManifest(1, "0".repeat(64), d.sha256, "ciphertext.age", d.size.toString(), TransferRules.CHUNK, d.hashes.size, d.sha256)
        val manifest = draft.copy(manifest_id = draft.digest(d.hashes))
        return DownloadRecord(id, peerId, pairingAttempt, TransferSource(d.blob_id, d.blob_id, d.sha256), "ciphertext.age", d.size, createdAt,
            state = DownloadState.PAUSED, manifest = manifest, hashes = d.hashes, chunks = chunks)
    }
    override fun toString() = "AttachmentRecord($state, [protected metadata])"
}
fun TrustDocument.attachment(id: String) = attachments.singleOrNull { it.id == id } ?: fail("ATTACHMENT_NOT_FOUND")
fun TrustDocument.replaceAttachment(r: AttachmentRecord): TrustDocument {
    demand(attachments.any { it.id == r.id }, "ATTACHMENT_NOT_FOUND")
    return copy(version = maxOf(version, 5), attachments = attachments.map { if (it.id == r.id) r else it })
}
fun requireAttachmentContinuation(before: TrustDocument, after: TrustDocument) {
    before.attachments.forEach { old ->
        val next = after.attachments.find { it.id == old.id }
        if (next == null) demand(old.state == AttachmentState.CANCELLED && old.exports.none { it.state in TransferExport.inFlight || it.state == ExportState.UNCERTAIN }, "ATTACHMENT_STATE_INVALID")
        else {
            demand(next.messageId == old.messageId && next.peerId == old.peerId && next.pairingAttempt == old.pairingAttempt && next.direction == old.direction && next.text == old.text && next.createdAt == old.createdAt, "ATTACHMENT_STATE_INVALID")
            old.secret?.let { demand(next.secret == it, "ATTACHMENT_STATE_INVALID") }; old.receipt?.let { demand(next.receipt == it, "ATTACHMENT_STATE_INVALID") }
            if (old.remoteStored) demand(next.remoteStored, "ATTACHMENT_STATE_INVALID")
        }
    }
}
