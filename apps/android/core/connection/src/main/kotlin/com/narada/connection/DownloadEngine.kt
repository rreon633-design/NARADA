package com.narada.connection

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

/** Persisted queue/checkpoints are the authority. One application-owned runner calls run at a time;
 * every transition also compares its durable run token to fence cancellation/restart/late writes. */
class DownloadEngine(
    private val repository: TrustRepository, private val gateway: TransfersGateway, private val storage: TransferChunks,
    private val now: () -> Long = System::currentTimeMillis, private val changed: suspend () -> Unit = {},
) {
    suspend fun enqueue(peerId: String, pairingAttempt: String, source: TransferSource, name: String, size: Long, confirmed: Boolean): DownloadRecord {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED"); source.validate(); FileRules.newName(name)
        demand(size in 0..TransferRules.MAX_BYTES, "TRANSFER_LIMIT")
        val r = DownloadRecord(UUID.randomUUID().toString(), peerId, pairingAttempt, source, name, size, now()); r.validate()
        var admitted = r
        repository.mutate { doc ->
            val p = doc.peer(peerId)
            demand(doc.enabled && p.status == TrustStatus.ACTIVE && p.attemptId == pairingAttempt && !p.blocked, "TRANSFER_SCOPE_CHANGED")
            demand("transfers.v1" in p.remoteOperations, "TRANSFERS_UNSUPPORTED")
            demand(p.remotePermissions.roots.singleOrNull { it.root_id == source.root_id }?.capabilities?.containsAll(listOf("browse", "read")) == true, "ACCESS_DENIED")
            // Exact source identity, not name/hash similarity. Never widen a pin's authority or
            // silently restart paused work. Check consent/current grants even when reusing a copy.
            val existing = doc.downloads.firstOrNull { it.peerId == peerId && it.pairingAttempt == pairingAttempt &&
                it.source == source && !it.syncRequired && it.state !in setOf(DownloadState.CANCELLED, DownloadState.CORRUPT) }
            if (existing != null) {
                demand(existing.name == name && existing.size == size, "MANIFEST_MISMATCH")
                admitted = existing
                doc
            } else {
            demand(doc.downloads.size < TransferRules.MAX_JOBS && doc.downloads.sumOf { it.size } + doc.attachments.sumOf { it.reservedBytes } + size <= TransferRules.QUOTA, "TRANSFER_LOCAL_QUOTA")
            doc.copy(version = maxOf(doc.version, 3), downloads = doc.downloads + r)
            }
        }
        changed(); return admitted // Intent and immutable source arguments precede even PREPARE.
    }
    suspend fun recover() {
        if (repository.read() == null) return
        repository.mutate { doc -> doc.copy(version = maxOf(doc.version, 3), downloads = doc.downloads.map { r ->
            val stopped = if (r.state in setOf(DownloadState.RUNNING, DownloadState.QUEUED)) r.copy(state = DownloadState.PAUSED, runId = null, error = "RESTART_PAUSED") else r
            stopped.copy(exports = stopped.exports.map { e -> when (e.state) { ExportState.PICKER_PENDING -> e.copy(state = ExportState.NOT_CREATED, error = "EXPORT_PICKER_CANCELLED"); ExportState.INTENT, ExportState.CREATED -> e.copy(state = ExportState.UNCERTAIN, error = "EXPORT_INTERRUPTED"); else -> e } })
        }) }
        changed() // No network, automatic resend, provider creation or reconstructed encryption state.
    }
    private suspend fun record(id: String): Pair<String, DownloadRecord> {
        val doc = repository.read() ?: fail("IDENTITY_NOT_READY"); return doc.storeId to doc.download(id)
    }
    private suspend fun running(id: String, token: String, guard: () -> Unit): Pair<String, DownloadRecord> {
        currentCoroutineContext().ensureActive(); guard()
        return record(id).also { demand(it.second.state == DownloadState.RUNNING && it.second.runId == token, "TRANSFER_STOPPED") }
    }
    private suspend fun update(id: String, token: String, guard: () -> Unit, change: (DownloadRecord) -> DownloadRecord): DownloadRecord {
        currentCoroutineContext().ensureActive(); guard()
        val doc = repository.mutate { old ->
            guard()
            val r = old.download(id); demand(r.state == DownloadState.RUNNING && r.runId == token, "TRANSFER_STOPPED")
            old.replaceDownload(change(r))
        }
        changed(); return doc.download(id)
    }
    suspend fun run(id: String, guard: () -> Unit = {}): DownloadRecord {
        canonicalUuid(id); val token = UUID.randomUUID().toString()
        repository.mutate { doc ->
            val r = doc.download(id); demand(r.state == DownloadState.QUEUED && r.receipt == null, "TRANSFER_STOPPED")
            doc.replaceDownload(r.copy(state = DownloadState.RUNNING, runId = token, error = null))
        }; changed()
        return try { runAttempt(id, token, guard) }
        catch (e: ConnectionFailure) {
            if (e.code in setOf("LOCAL_COPY_CHANGED", "LOCAL_COPY_MISSING")) markCorrupt(id, token, e.code)
            throw e
        }
    }
    private suspend fun markCorrupt(id: String, token: String, code: String) {
        withContext(NonCancellable) {
            repository.mutate { doc -> val r = doc.download(id)
                if (r.state == DownloadState.RUNNING && r.runId == token) doc.replaceDownload(r.copy(state = DownloadState.CORRUPT, runId = null, error = code)) else doc
            }; changed()
        }
    }
    private suspend fun runAttempt(id: String, token: String, guard: () -> Unit): DownloadRecord {
        var (owner, r) = running(id, token, guard)
        storage.reserve(owner, r)
        val resumeManifest = r.manifest != null
        if (r.manifest == null) {
            val bundle = gateway.prepare(r)
            r = update(id, token, guard) { it.copy(manifest = bundle.manifest, hashes = bundle.hashes) }
        }
        val m = r.manifest ?: fail("INVALID_MANIFEST")
        val found = mutableListOf<Int>()
        for (i in 0 until m.chunk_count) {
            running(id, token, guard)
            val data = storage.read(owner, r, i) ?: continue
            try { demand(data.size == m.length(i) && sha256(data) == r.hashes[i], "LOCAL_COPY_CHANGED"); found.add(i) } finally { data.fill(0) }
        }
        r = update(id, token, guard) { it.copy(chunks = found.toList()) }
        storage.reserve(owner, r)
        if (resumeManifest && found.size < m.chunk_count) {
            // Exact original transfer/source. The server must not replace an expired/stale manifest.
            val bundle = gateway.prepare(r)
            demand(bundle.manifest == m && bundle.hashes == r.hashes, "MANIFEST_MISMATCH")
        }
        for (i in 0 until m.chunk_count) {
            r = running(id, token, guard).second
            if (i in r.chunks) continue
            val bytes = gateway.chunk(r, i)
            try {
                running(id, token, guard)
                demand(bytes.size == m.length(i) && sha256(bytes) == r.hashes[i], "HASH_MISMATCH")
                storage.write(owner, r, i, bytes)
                r = update(id, token, guard) { it.copy(chunks = (it.chunks + i).distinct().sorted()) }
            } finally { bytes.fill(0) }
        }
        running(id, token, guard)
        try { verifyBytes(owner, r, guard) }
        catch (e: ConnectionFailure) { if (e.code == "HASH_MISMATCH") markCorrupt(id, token, e.code); throw e }
        // Local verification of already received bytes may complete offline. It grants no new remote access.
        val complete = update(id, token, guard) { it.copy(state = DownloadState.VERIFIED, runId = null, error = null,
            receipt = PrivateReceipt(m.manifest_id, m.sha256, m.bytes, now())) }
        return complete // Only this durable, full-hash receipt means a private copy is saved.
    }
    suspend fun finish(id: String) {
        val r = record(id).second; demand(r.state == DownloadState.VERIFIED && r.receipt != null, "TRANSFER_NOT_VERIFIED")
        if (r.remoteFinished) return
        gateway.finish(r)
        repository.mutate { doc ->
            val current = doc.download(id); demand(current.state == DownloadState.VERIFIED && current.receipt == r.receipt, "TRANSFER_STOPPED")
            doc.replaceDownload(current.copy(remoteFinished = true))
        }; changed()
    }
    /** Caller stops/joins its runner before changing durable control state. */
    suspend fun pause(id: String, error: String? = null) {
        repository.mutate { doc -> val r = doc.download(id)
            if (r.state !in setOf(DownloadState.QUEUED, DownloadState.RUNNING, DownloadState.PAUSED)) doc else doc.replaceDownload(r.copy(state = DownloadState.PAUSED, runId = null, error = error))
        }; changed()
    }
    suspend fun cancel(id: String, confirmed: Boolean) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { doc -> val r = doc.download(id)
            demand(r.state != DownloadState.VERIFIED, "TRANSFER_ALREADY_VERIFIED")
            doc.replaceDownload(r.copy(state = DownloadState.CANCELLED, runId = null, error = null))
        }; changed() // Local only; does not undo remote snapshots, source files or exports.
    }
    suspend fun queueResume(id: String, confirmed: Boolean) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { doc -> val r = doc.download(id); demand(r.state == DownloadState.PAUSED && r.receipt == null, "TRANSFER_STOPPED")
            doc.replaceDownload(r.copy(state = DownloadState.QUEUED, error = null))
        }; changed()
    }
    suspend fun verifyPrivate(id: String, guard: () -> Unit = {}): DownloadRecord {
        val (owner, r) = record(id); demand(r.state == DownloadState.VERIFIED && r.receipt != null, "TRANSFER_NOT_VERIFIED")
        try { verifyBytes(owner, r, guard) }
        catch (e: ConnectionFailure) {
            if (e.code in setOf("LOCAL_COPY_CHANGED", "LOCAL_COPY_MISSING", "HASH_MISMATCH")) {
                repository.mutate { doc -> val current = doc.download(id)
                    demand(current.receipt == r.receipt, "TRANSFER_STOPPED")
                    doc.replaceDownload(current.copy(state = DownloadState.CORRUPT, error = e.code))
                }; changed()
            }
            throw e
        }
        return r
    }
    private suspend fun verifyBytes(owner: String, r: DownloadRecord, guard: () -> Unit) {
        val m = r.manifest ?: fail("INVALID_MANIFEST"); m.verify(r.hashes)
        val full = MessageDigest.getInstance("SHA-256"); var count = 0L
        for (i in 0 until m.chunk_count) {
            currentCoroutineContext().ensureActive(); guard()
            val bytes = storage.read(owner, r, i) ?: fail("LOCAL_COPY_MISSING")
            try { demand(bytes.size == m.length(i) && sha256(bytes) == r.hashes[i], "LOCAL_COPY_CHANGED"); full.update(bytes); count += bytes.size } finally { bytes.fill(0) }
        }
        demand(count == m.bytes && full.digest().hex() == m.sha256, "HASH_MISMATCH")
    }
    suspend fun remove(id: String, confirmed: Boolean) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        val doc = repository.mutate { old -> val r = old.download(id)
            demand(!old.downloadReferenced(id), "PIN_REFERENCED")
            demand(r.state != DownloadState.RUNNING && r.exports.none { it.state in setOf(ExportState.PICKER_PENDING, ExportState.INTENT, ExportState.CREATED, ExportState.UNCERTAIN) }, "EXPORT_UNRESOLVED")
            old.replaceDownload(r.copy(state = DownloadState.CANCELLED, runId = null))
        }; changed() // Durable removal intent first: never continue displaying an available verified copy.
        storage.remove(doc.storeId, doc.download(id))
        repository.mutate { old -> demand(old.download(id).state == DownloadState.CANCELLED, "TRANSFER_STOPPED"); old.copy(version = maxOf(old.version, 3), downloads = old.downloads.filterNot { it.id == id }) }
        changed()
    }
}
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
