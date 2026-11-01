package com.narada.connection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Current desktop transfers.v1. Download-only on the phone; no inferred reverse route/owner API. */
object TransferRules {
    const val PATH = "/api/v1/transfers"
    const val CHUNK = 262_144
    const val MAX_BYTES = 67_108_864L
    const val QUOTA = 134_217_728L
    const val MAX_JOBS = 4
    const val MANIFEST_PAGE = 32
    fun size(value: String): Long {
        demand(value.matches(Regex("0|[1-9][0-9]{0,9}")), "INVALID_MANIFEST")
        val n = value.toLongOrNull() ?: fail("INVALID_MANIFEST")
        demand(n <= MAX_BYTES, "TRANSFER_LIMIT"); return n
    }
    fun hash(value: String) { demand(value.matches(Regex("[0-9a-f]{64}")), "INVALID_MANIFEST") }
    fun chunkPath(id: String, index: Int): String {
        canonicalUuid(id); demand(index in 0..255, "INVALID_CHUNK")
        return "$PATH/chunks/$id/$index"
    }
    fun uri(value: String) {
        demand(value.length in 1..2048 && value.startsWith("content://") && value.none { it.isISOControl() }, "INVALID_EXPORT_TARGET")
    }
}
data class DownloadChoice(val peerId: String, val pairingAttempt: String, val source: TransferSource, val name: String, val size: Long)
@Serializable data class TransferSource(val root_id: String, val object_id: String, val expected_version: String) {
    fun validate() { FileRules.id(root_id); FileRules.id(object_id); FileRules.version(expected_version) }
}
@Serializable data class TransferManifest(
    val version: Int, val manifest_id: String, val source_version: String, val name: String,
    val size: String, val chunk_size: Int, val chunk_count: Int, val sha256: String,
) {
    val bytes: Long get() = TransferRules.size(size)
    fun validate() {
        demand(version == 1, "PROTOCOL_INCOMPATIBLE"); TransferRules.hash(manifest_id); TransferRules.hash(sha256); FileRules.version(source_version)
        FileRules.newName(name)
        demand(chunk_size == TransferRules.CHUNK && chunk_count in 0..256 && chunk_count.toLong() == (bytes + chunk_size - 1) / chunk_size, "INVALID_MANIFEST")
        if (bytes == 0L) demand(sha256 == com.narada.connection.sha256(byteArrayOf()), "INVALID_MANIFEST")
    }
    fun length(index: Int): Int {
        validate(); demand(index in 0 until chunk_count, "INVALID_CHUNK")
        return minOf(TransferRules.CHUNK.toLong(), bytes - index.toLong() * TransferRules.CHUNK).toInt()
    }
    fun digest(hashes: List<String>): String {
        validate(); demand(hashes.size == chunk_count, "INVALID_MANIFEST"); hashes.forEach(TransferRules::hash)
        // Byte-compatible with serde_json's tuple in narada-files/transfers/mod.rs.
        val tuple = buildJsonArray {
            add(version); add(source_version); add(name); add(size); add(chunk_size); add(chunk_count); add(sha256)
            add(JsonArray(hashes.map(::JsonPrimitive)))
        }
        return com.narada.connection.sha256(tuple.toString().toByteArray(Charsets.UTF_8))
    }
    fun verify(hashes: List<String>) { demand(digest(hashes) == manifest_id, "MANIFEST_MISMATCH") }
}
@Serializable data class TransferStatusReply(
    val transfer_id: String, val state: String, val manifest: TransferManifest?, val missing: List<Int>,
    val verified_bytes: String, val error_code: String?, val result: JsonElement?,
) {
    fun source(record: DownloadRecord): TransferManifest {
        demand(transfer_id == record.id && state in setOf("ready", "completed"), "TRANSFER_NOT_READY")
        demand(missing.isEmpty() && error_code == null && result == null, "INVALID_TRANSFER_RESPONSE")
        val m = manifest ?: fail("INVALID_TRANSFER_RESPONSE"); m.validate()
        demand(verified_bytes == m.size && m.source_version == record.source.expected_version && m.name == record.name && m.bytes == record.size, "MANIFEST_MISMATCH")
        record.manifest?.let { demand(it == m, "MANIFEST_MISMATCH") }
        return m // Remote verified_bytes is SOURCE snapshot metadata, never phone progress.
    }
}
@Serializable data class TransferManifestPage(val transfer_id: String, val manifest_id: String, val offset: Int, val hashes: List<String>, val next_offset: Int?) {
    fun validate(id: String, manifest: TransferManifest, expectedOffset: Int) {
        demand(transfer_id == id && manifest_id == manifest.manifest_id && offset == expectedOffset && offset in 0..manifest.chunk_count && offset % 32 == 0, "INVALID_MANIFEST")
        val end = minOf(offset + 32, manifest.chunk_count)
        demand(hashes.size == end - offset && next_offset == if (end < manifest.chunk_count) end else null, "INVALID_MANIFEST")
        hashes.forEach(TransferRules::hash)
    }
}
@Serializable data class TransferWire(val hello: Hello = Hello(), val version: Int = 1, val action: JsonObject)
@Serializable data class TransferFinished(val acknowledged: Boolean)

@Serializable enum class DownloadState { QUEUED, RUNNING, PAUSED, VERIFIED, CANCELLED, CORRUPT }
@Serializable enum class ExportState { PICKER_PENDING, INTENT, CREATED, UNCERTAIN, VERIFIED, NOT_CREATED, DISMISSED_UNCONFIRMED }
@Serializable data class ExportRecord(
    val id: String, val name: String, val createdAt: Long, val state: ExportState = ExportState.PICKER_PENDING,
    val tree: String? = null, val document: String? = null, val verifiedAt: Long? = null, val error: String? = null,
) {
    fun validate() {
        canonicalUuid(id); FileRules.newName(name); demand(createdAt > 0, "TRUST_STORE_INVALID")
        tree?.let(TransferRules::uri); document?.let(TransferRules::uri)
        demand(state != ExportState.PICKER_PENDING || tree == null && document == null, "TRUST_STORE_INVALID")
        demand(state !in setOf(ExportState.INTENT, ExportState.CREATED, ExportState.VERIFIED) || tree != null, "TRUST_STORE_INVALID")
        demand(state !in setOf(ExportState.CREATED, ExportState.VERIFIED) || document != null, "TRUST_STORE_INVALID")
        demand((state == ExportState.VERIFIED) == (verifiedAt != null) && (verifiedAt == null || verifiedAt >= createdAt), "TRUST_STORE_INVALID")
        demand(error == null || error.matches(Regex("[A-Z_]{1,64}")), "TRUST_STORE_INVALID")
    }
    override fun toString() = "ExportRecord([protected destination], $state)"
}
@Serializable data class PrivateReceipt(val manifestId: String, val sha256: String, val size: Long, val verifiedAt: Long)
@Serializable data class DownloadRecord(
    val id: String, val peerId: String, val pairingAttempt: String, val source: TransferSource,
    val name: String, val size: Long, val createdAt: Long, val state: DownloadState = DownloadState.QUEUED,
    val runId: String? = null, val manifest: TransferManifest? = null, val hashes: List<String> = emptyList(),
    val chunks: List<Int> = emptyList(), val receipt: PrivateReceipt? = null, val remoteFinished: Boolean = false,
    val error: String? = null, val exports: List<ExportRecord> = emptyList(), val syncRequired: Boolean = false,
) {
    val stagedComplete: Boolean get() = manifest?.let { chunks.size == it.chunk_count } == true
    val verifiedBytes: Long get() = if (state in setOf(DownloadState.CORRUPT, DownloadState.CANCELLED)) 0 else manifest?.let { m -> chunks.sumOf { m.length(it).toLong() } } ?: 0
    fun validate() {
        canonicalUuid(id); validHash(peerId); canonicalUuid(pairingAttempt); source.validate(); FileRules.newName(name)
        demand(size in 0..TransferRules.MAX_BYTES && createdAt > 0, "TRUST_STORE_INVALID")
        demand((state == DownloadState.RUNNING) == (runId != null), "TRUST_STORE_INVALID"); runId?.let(::canonicalUuid)
        demand(chunks.size <= 256 && chunks == chunks.distinct().sorted(), "TRUST_STORE_INVALID")
        val m = manifest
        if (m == null) demand(hashes.isEmpty() && chunks.isEmpty() && receipt == null, "TRUST_STORE_INVALID") else {
            m.validate(); m.verify(hashes)
            demand(m.source_version == source.expected_version && m.name == name && m.bytes == size && chunks.all { it in 0 until m.chunk_count }, "TRUST_STORE_INVALID")
        }
        receipt?.let { r -> demand(m != null && r.manifestId == m.manifest_id && r.sha256 == m.sha256 && r.size == size && r.verifiedAt >= createdAt && chunks.size == m.chunk_count, "TRUST_STORE_INVALID") }
        demand(state != DownloadState.VERIFIED || receipt != null, "TRUST_STORE_INVALID")
        demand(!remoteFinished || receipt != null, "TRUST_STORE_INVALID")
        demand(error == null || error.matches(Regex("[A-Z_]{1,64}")), "TRUST_STORE_INVALID")
        demand(exports.size <= 2 && exports.map { it.id }.distinct().size == exports.size && (exports.isEmpty() || receipt != null), "TRUST_STORE_INVALID")
        exports.forEach(ExportRecord::validate)
    }
    override fun toString() = "DownloadRecord($id, $state, [protected metadata])"
}
fun TrustDocument.download(id: String) = downloads.singleOrNull { it.id == id } ?: fail("TRANSFER_NOT_FOUND")
fun TrustDocument.replaceDownload(record: DownloadRecord): TrustDocument {
    demand(downloads.any { it.id == record.id }, "TRANSFER_NOT_FOUND")
    return copy(version = maxOf(version, 3), downloads = downloads.map { if (it.id == record.id) record else it })
}
val transferErrorCodes = fileErrorCodes + setOf("TRANSFER_LIMIT", "TRANSFER_QUOTA", "TRANSFER_EXPIRED", "TRANSFER_NOT_FOUND", "TRANSFER_NOT_READY", "TRANSFER_UNSUPPORTED", "TRANSFER_INTERRUPTED", "SOURCE_CHANGED", "SOURCE_PREPARATION_INTERRUPTED", "STAGING_CHANGED", "HASH_MISMATCH", "INVALID_CHUNK", "INVALID_MANIFEST", "DATA_BUSY", "SESSION_INVALIDATED", "CERTIFICATE_REQUIRED", "NO_PEER_ROUTE")
