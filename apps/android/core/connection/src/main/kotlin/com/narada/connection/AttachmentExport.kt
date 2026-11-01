package com.narada.connection

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*

/** Inert create-new SAF export. Complete age authentication precedes the first provider create;
 * uncertain external documents are retained and are never automatically retried or deleted. */
class AttachmentExport(
    private val repository: TrustRepository, private val attachments: AttachmentsEngine,
    private val now: () -> Long = System::currentTimeMillis, private val changed: suspend () -> Unit = {},
) {
    suspend fun prepare(id: String, name: String, confirmed: Boolean): ExportRecord {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED"); FileRules.newName(name)
        val e = ExportRecord(UUID.randomUUID().toString(), name, now())
        repository.mutate { d -> val r = d.attachment(id)
            demand(r.available && r.runId == null, "ATTACHMENT_NOT_VERIFIED")
            demand(r.exports.size < 2, "EXPORT_HISTORY_FULL")
            demand(r.exports.none { it.state in TransferExport.inFlight || it.state == ExportState.UNCERTAIN }, "EXPORT_UNRESOLVED")
            d.replaceAttachment(r.copy(exports = r.exports + e))
        }; changed(); return e
    }
    private suspend fun update(id: String, exportId: String, expected: Set<ExportState>, change: (ExportRecord) -> ExportRecord) {
        repository.mutate { d ->
            val r = d.attachment(id); val e = r.exports.singleOrNull { it.id == exportId } ?: fail("EXPORT_STOPPED")
            demand(e.state in expected, "EXPORT_STOPPED"); d.replaceAttachment(r.copy(exports = r.exports.map { if (it.id == exportId) change(it) else it }))
        }; changed()
    }
    suspend fun pickerCancelled(id: String, exportId: String) = update(id, exportId, setOf(ExportState.PICKER_PENDING)) { it.copy(state = ExportState.NOT_CREATED, error = "EXPORT_PICKER_CANCELLED") }
    suspend fun dismiss(id: String, exportId: String, confirmed: Boolean) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        update(id, exportId, setOf(ExportState.UNCERTAIN)) { it.copy(state = ExportState.DISMISSED_UNCONFIRMED) }
    }
    suspend fun forget(id: String, exportId: String, confirmed: Boolean) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { d ->
            val r = d.attachment(id); val e = r.exports.singleOrNull { it.id == exportId } ?: fail("EXPORT_STOPPED")
            demand(e.state in setOf(ExportState.VERIFIED, ExportState.NOT_CREATED, ExportState.DISMISSED_UNCONFIRMED), "EXPORT_UNRESOLVED")
            d.replaceAttachment(r.copy(exports = r.exports.filterNot { it.id == exportId }))
        }; changed()
    }
    suspend fun execute(id: String, exportId: String, tree: String, provider: NewDocumentProvider, guard: () -> Unit = {}) {
        var creationMayHaveRun = false
        var plain: ByteArray? = null
        try {
            TransferRules.uri(tree); currentCoroutineContext().ensureActive(); guard()
            val bytes = attachments.verifiedPlain(id, guard = guard); plain = bytes
            val r = repository.read()?.attachment(id) ?: fail("ATTACHMENT_NOT_FOUND")
            val secret = r.secret ?: fail("INVALID_ATTACHMENT")
            val request = r.exports.singleOrNull { it.id == exportId && it.state == ExportState.PICKER_PENDING } ?: fail("EXPORT_STOPPED")
            update(id, exportId, setOf(ExportState.PICKER_PENDING)) { it.copy(state = ExportState.INTENT, tree = tree) }
            provider.preflight(tree, request.name)
            currentCoroutineContext().ensureActive(); guard()
            creationMayHaveRun = true
            val document = provider.create(tree, request.name); TransferRules.uri(document)
            currentCoroutineContext().ensureActive(); guard()
            update(id, exportId, setOf(ExportState.INTENT)) { it.copy(state = ExportState.CREATED, document = document) }
            provider.writer(document).use { output ->
                var offset = 0
                while (offset < bytes.size) { currentCoroutineContext().ensureActive(); guard(); val n = minOf(8192, bytes.size - offset); output.write(bytes, offset, n); offset += n }
                output.flush()
            }
            val hash = MessageDigest.getInstance("SHA-256"); var count = 0L
            provider.reader(document).use { input ->
                val chunk = ByteArray(8192)
                try { while (true) {
                    currentCoroutineContext().ensureActive(); guard(); val n = input.read(chunk); if (n < 0) break
                    demand(n > 0 && count + n <= secret.plaintext_size, "EXPORT_VERIFICATION_FAILED"); hash.update(chunk, 0, n); count += n
                } } finally { chunk.fill(0) }
            }
            demand(count == secret.plaintext_size && hash.digest().hex() == secret.plaintext_sha256, "EXPORT_VERIFICATION_FAILED")
            currentCoroutineContext().ensureActive(); guard()
            update(id, exportId, setOf(ExportState.CREATED)) { guard(); it.copy(state = ExportState.VERIFIED, verifiedAt = now(), error = null) }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                val r = repository.read()?.attachments?.find { it.id == id }
                if (r?.exports?.any { it.id == exportId && it.state in TransferExport.inFlight } == true) update(id, exportId, TransferExport.inFlight) {
                    it.copy(state = if (creationMayHaveRun) ExportState.UNCERTAIN else ExportState.NOT_CREATED, error = (e as? ConnectionFailure)?.code ?: if (e is CancellationException) "EXPORT_INTERRUPTED" else "PROVIDER_UNAVAILABLE")
                }
            }; throw e
        } finally { plain?.fill(0); provider.close() }
    }
    suspend fun interrupted(id: String, exportId: String) {
        val r = repository.read()?.attachments?.find { it.id == id } ?: return
        if (r.exports.any { it.id == exportId && it.state in TransferExport.inFlight }) update(id, exportId, TransferExport.inFlight) {
            it.copy(state = if (it.state == ExportState.PICKER_PENDING) ExportState.NOT_CREATED else ExportState.UNCERTAIN, error = "EXPORT_INTERRUPTED")
        }
    }
}
