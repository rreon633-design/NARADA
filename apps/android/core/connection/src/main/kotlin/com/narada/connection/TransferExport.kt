package com.narada.connection

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

/** Android implements DocumentsContract create-new only. No arbitrary existing-document writer. */
interface NewDocumentProvider : AutoCloseable {
    fun preflight(tree: String, name: String)
    fun create(tree: String, name: String): String
    fun writer(document: String): OutputStream
    fun reader(document: String): InputStream
    fun cancel()
    override fun close()
}
class TransferExport(
    private val repository: TrustRepository, private val downloads: DownloadEngine, private val storage: TransferChunks,
    private val now: () -> Long = System::currentTimeMillis, private val changed: suspend () -> Unit = {},
) {
    suspend fun prepare(id: String, name: String, confirmed: Boolean): ExportRecord {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED"); FileRules.newName(name)
        val request = ExportRecord(UUID.randomUUID().toString(), name, now())
        repository.mutate { doc ->
            val r = doc.download(id); demand(r.state == DownloadState.VERIFIED && r.receipt != null, "TRANSFER_NOT_VERIFIED")
            demand(r.exports.none { it.state in inFlight || it.state == ExportState.UNCERTAIN }, "EXPORT_UNRESOLVED")
            demand(r.exports.size < 2, "EXPORT_HISTORY_FULL")
            doc.replaceDownload(r.copy(exports = r.exports + request))
        }; changed(); return request
    }
    private suspend fun update(id: String, exportId: String, expected: Set<ExportState>, change: (ExportRecord) -> ExportRecord) {
        repository.mutate { doc -> val r = doc.download(id); val e = r.exports.singleOrNull { it.id == exportId } ?: fail("EXPORT_STOPPED")
            demand(e.state in expected, "EXPORT_STOPPED")
            doc.replaceDownload(r.copy(exports = r.exports.map { if (it.id == exportId) change(it) else it }))
        }; changed()
    }
    suspend fun pickerCancelled(id: String, exportId: String) {
        update(id, exportId, setOf(ExportState.PICKER_PENDING)) { it.copy(state = ExportState.NOT_CREATED, error = "EXPORT_PICKER_CANCELLED") }
    }
    suspend fun dismiss(id: String, exportId: String, acknowledged: Boolean) {
        demand(acknowledged, "LOCAL_CONFIRMATION_REQUIRED")
        update(id, exportId, setOf(ExportState.UNCERTAIN)) { it.copy(state = ExportState.DISMISSED_UNCONFIRMED) }
    }
    /** Explicitly remove a reviewed local receipt only. No provider IO, deletion or recall. */
    suspend fun forget(id: String, exportId: String, confirmed: Boolean) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { doc -> val r = doc.download(id); val e = r.exports.singleOrNull { it.id == exportId } ?: fail("EXPORT_STOPPED")
            demand(e.state in setOf(ExportState.VERIFIED, ExportState.NOT_CREATED, ExportState.DISMISSED_UNCONFIRMED), "EXPORT_UNRESOLVED")
            doc.replaceDownload(r.copy(exports = r.exports.filterNot { it.id == exportId }))
        }; changed()
    }
    /** A timeout can leave a provider still finishing admitted IO. Never retry creation or delete an
     * uncertain document. Caller quarantines the one worker until it actually exits. */
    suspend fun execute(id: String, exportId: String, tree: String, provider: NewDocumentProvider, guard: () -> Unit = {}) {
        var creationMayHaveRun = false
        try {
            TransferRules.uri(tree); currentCoroutineContext().ensureActive(); guard()
            val r = downloads.verifyPrivate(id, guard) // Full local verification BEFORE any provider publication.
            val request = r.exports.singleOrNull { it.id == exportId && it.state == ExportState.PICKER_PENDING } ?: fail("EXPORT_STOPPED")
            update(id, exportId, setOf(ExportState.PICKER_PENDING)) { it.copy(state = ExportState.INTENT, tree = tree) }
            provider.preflight(tree, request.name)
            currentCoroutineContext().ensureActive(); guard()
            creationMayHaveRun = true
            val document = provider.create(tree, request.name); TransferRules.uri(document)
            currentCoroutineContext().ensureActive(); guard()
            update(id, exportId, setOf(ExportState.INTENT)) { it.copy(state = ExportState.CREATED, document = document) }
            val owner = repository.read()?.storeId ?: fail("IDENTITY_NOT_READY")
            val m = r.manifest ?: fail("INVALID_MANIFEST")
            val sent = MessageDigest.getInstance("SHA-256"); var written = 0L
            provider.writer(document).use { output ->
                for (i in 0 until m.chunk_count) {
                    currentCoroutineContext().ensureActive(); guard()
                    val bytes = storage.read(owner, r, i) ?: fail("LOCAL_COPY_MISSING")
                    try {
                        demand(bytes.size == m.length(i) && sha256(bytes) == r.hashes[i], "LOCAL_COPY_CHANGED")
                        currentCoroutineContext().ensureActive(); guard(); output.write(bytes); sent.update(bytes); written += bytes.size
                    } finally { bytes.fill(0) }
                }
                output.flush()
            }
            demand(written == m.bytes && sent.digest().hex() == m.sha256, "HASH_MISMATCH")
            val observed = MessageDigest.getInstance("SHA-256"); var read = 0L
            provider.reader(document).use { input ->
                val bytes = ByteArray(8192)
                try { while (true) {
                    currentCoroutineContext().ensureActive(); guard()
                    val n = input.read(bytes); if (n < 0) break
                    demand(n > 0 && read + n <= m.bytes, "EXPORT_VERIFICATION_FAILED")
                    observed.update(bytes, 0, n); read += n
                } } finally { bytes.fill(0) }
            }
            demand(read == m.bytes && observed.digest().hex() == m.sha256, "EXPORT_VERIFICATION_FAILED")
            currentCoroutineContext().ensureActive(); guard()
            update(id, exportId, setOf(ExportState.CREATED)) { guard(); it.copy(state = ExportState.VERIFIED, verifiedAt = now(), error = null) }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                val code = (e as? ConnectionFailure)?.code ?: if (e is CancellationException) "EXPORT_INTERRUPTED" else "PROVIDER_UNAVAILABLE"
                val current = repository.read()?.downloads?.find { it.id == id }
                if (current != null) {
                    if (code in setOf("LOCAL_COPY_CHANGED", "LOCAL_COPY_MISSING", "HASH_MISMATCH")) repository.mutate { doc -> doc.replaceDownload(doc.download(id).copy(state = DownloadState.CORRUPT, error = code)) }
                    if (current.exports.any { it.id == exportId && it.state in inFlight }) {
                        update(id, exportId, inFlight) { it.copy(state = if (creationMayHaveRun) ExportState.UNCERTAIN else ExportState.NOT_CREATED, error = code) }
                    }
                }
            }
            throw e
        } finally { provider.close() }
    }
    /** Used by a bounded caller even if a provider thread ignores interruption. Fences late receipts. */
    suspend fun interrupted(id: String, exportId: String) {
        val r = repository.read()?.downloads?.find { it.id == id } ?: return
        if (r.exports.any { it.id == exportId && it.state in inFlight }) update(id, exportId, inFlight) { it.copy(state = if (it.state == ExportState.PICKER_PENDING) ExportState.NOT_CREATED else ExportState.UNCERTAIN, error = "EXPORT_INTERRUPTED") }
    }
    companion object { val inFlight = setOf(ExportState.PICKER_PENDING, ExportState.INTENT, ExportState.CREATED) }
}
