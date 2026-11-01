package com.narada.connection

import java.util.UUID
import kotlinx.coroutines.*

/** A metadata snapshot is staged separately and atomically published only when complete. Pins own
 * references, not paths: refresh stages another immutable download and never replaces old bytes. */
class OfflineEngine(
    private val repository: TrustRepository, private val gateway: SyncGateway,
    private val now: () -> Long = System::currentTimeMillis, private val changed: suspend () -> Unit = {},
) {
    private suspend fun start(peerId: String, rootId: String, forceSnapshot: Boolean, guard: () -> Unit, expectedWork: SyncWork? = null): SyncCache {
        val after = repository.mutate { doc ->
            guard(); val peer = doc.peer(peerId); SyncRules.authority(doc, peerId, peer.attemptId, rootId)
            val scope = SyncScope.from(peer)
            val old = doc.syncCaches.find { it.peerId == peerId && it.rootId == rootId } ?: run {
                demand(doc.syncCaches.size < SyncRules.MAX_ROOTS, "SYNC_LOCAL_LIMIT"); SyncCache(peerId, rootId)
            }
            if (expectedWork != null) {
                // A late expiry belongs only to the exact admitted cursor work. It must not
                // restart/replace a newer refresh, or recreate a cache explicitly forgotten.
                demand(old.work == expectedWork, "SYNC_STOPPED")
                expectedWork.scope.check(doc, rootId)
            }
            val resume = old.work?.takeIf { !forceSnapshot && it.scope == scope && now() >= it.startedAt && now() - it.startedAt < 110_000 }
            val incremental = !forceSnapshot && old.scope == scope && old.cursor != null && old.refreshedAt != null
            val work = resume ?: SyncWork(UUID.randomUUID().toString(), scope, if (incremental) "changes" else "snapshot", if (incremental) old.cursor else null, if (incremental) old.fence else null, if (incremental) old.entries else emptyList(), now())
            doc.replaceSync(old.copy(work = work, error = null))
        }; changed(); return after.syncCache(peerId, rootId)
    }
    suspend fun refresh(peerId: String, rootId: String, confirmed: Boolean, forceSnapshot: Boolean = false, guard: () -> Unit = {}): SyncCache {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        var cache = start(peerId, rootId, forceSnapshot, guard)
        var restarted = false
        try {
            repeat(16) {
                currentCoroutineContext().ensureActive(); guard()
                val work = cache.work ?: return cache
                val ticket = SyncTicket(peerId, rootId, work)
                val result = try { gateway.page(ticket) }
                catch (e: ConnectionFailure) {
                    if (e.code == "CURSOR_EXPIRED" && !restarted) {
                        restarted = true; cache = start(peerId, rootId, true, guard, work); return@repeat
                    }
                    throw e
                }
                result.page.validate(SyncRequest(work.kind, rootId, work.cursor), work.fence)
                demand(result.scope == work.scope, "SYNC_SCOPE_CHANGED")
                val page = result.page
                val entries = if (work.kind == "snapshot") work.entries + page.entries else {
                    val values = work.entries.associateBy { it.object_id }.toMutableMap()
                    page.events.forEach { e -> if (e.kind == "deleted") values.remove(e.object_id) else values[e.object_id] = e.entry ?: fail("INVALID_SYNC_RESPONSE") }
                    values.values.toList()
                }
                SyncRules.entries(entries, complete = page.next_cursor == null)
                val after = repository.mutate { doc ->
                    guard(); result.scope.check(doc, rootId)
                    val old = doc.syncCache(peerId, rootId); demand(old.work == work, "SYNC_STOPPED")
                    if (page.next_cursor != null) doc.replaceSync(old.copy(work = work.copy(cursor = page.next_cursor, fence = page.fence, entries = entries), error = null))
                    else {
                        val observedAt = now()
                        val complete = old.copy(entries = entries, scope = result.scope, cursor = page.changes_cursor, fence = page.fence, refreshedAt = observedAt, work = null, error = null)
                        doc.replaceSync(complete).copy(pins = doc.pins.map { p ->
                            if (p.peerId != peerId || p.rootId != rootId || p.pairingAttempt != result.scope.attempt) p
                            else { val observed = entries.find { it.object_id == p.objectId }; p.copy(observed = observed, tombstone = observed == null, observedAt = observedAt) }
                        })
                    }
                }
                cache = after.syncCache(peerId, rootId); changed()
                if (cache.work == null) return cache
            }
            fail("SYNC_PAGE_LIMIT")
        } catch (e: Exception) {
            withContext(NonCancellable) {
                repository.mutate { doc ->
                    val old = doc.syncCaches.find { it.peerId == peerId && it.rootId == rootId }
                    if (old == null || cache.work == null || old.work?.id != cache.work?.id) doc
                    else doc.replaceSync(old.copy(error = if (e is CancellationException) "SYNC_INTERRUPTED" else (e as? ConnectionFailure)?.code ?: "SYNC_UNAVAILABLE"))
                }; changed()
            }
            throw e
        }
    }
    /** Pin and initial candidate are committed together, before any PREPARE_SYNC is sent. */
    suspend fun pin(peerId: String, rootId: String, objectId: String, confirmed: Boolean): OfflinePin {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED"); val pinId = UUID.randomUUID().toString()
        val after = repository.mutate { doc ->
            val cache = doc.syncCache(peerId, rootId); (cache.scope ?: fail("SYNC_INCOMPLETE")).check(doc, rootId)
            demand(cache.refreshedAt != null && cache.work == null && cache.error == null, "SYNC_INCOMPLETE")
            val entry = cache.entries.singleOrNull { it.object_id == objectId } ?: fail("OBJECT_NOT_FOUND")
            demand(doc.pins.size < SyncRules.MAX_PINS && doc.pins.none { it.peerId == peerId && it.rootId == rootId && it.objectId == objectId }, "PIN_LIMIT")
            val scope = cache.scope
            val download = candidate(doc, peerId, scope.attempt, rootId, entry)
            val pin = OfflinePin(pinId, peerId, scope.attempt, rootId, objectId, now(), entry, now(), candidate = download.id)
            doc.copy(version = maxOf(doc.version, 6), downloads = doc.downloads + download, pins = doc.pins + pin)
        }; changed(); return after.pin(pinId)
    }
    private fun candidate(doc: TrustDocument, peerId: String, attempt: String, rootId: String, entry: SyncEntry): DownloadRecord {
        SyncRules.authority(doc, peerId, attempt, rootId); entry.validate(); demand(entry.kind == "file", "PIN_FILE_REQUIRED")
        val size = entry.size.toLongOrNull() ?: fail("TRANSFER_LIMIT"); demand(size in 0..TransferRules.MAX_BYTES, "TRANSFER_LIMIT")
        demand(doc.downloads.size < TransferRules.MAX_JOBS && doc.downloads.sumOf { it.size } + doc.attachments.sumOf { it.reservedBytes } + size <= TransferRules.QUOTA, "TRANSFER_LOCAL_QUOTA")
        return DownloadRecord(UUID.randomUUID().toString(), peerId, attempt, TransferSource(rootId, entry.object_id, entry.version), entry.name, size, now(), syncRequired = true)
    }
    suspend fun refreshPin(id: String, confirmed: Boolean): DownloadRecord {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        val after = repository.mutate { doc ->
            val p = doc.pin(id); demand(p.candidate == null, "PIN_CANDIDATE_EXISTS")
            val cache = doc.syncCache(p.peerId, p.rootId); (cache.scope ?: fail("SYNC_INCOMPLETE")).check(doc, p.rootId)
            demand(cache.scope.attempt == p.pairingAttempt && cache.work == null && cache.error == null, "SYNC_SCOPE_CHANGED")
            val entry = cache.entries.find { it.object_id == p.objectId } ?: fail("PIN_SOURCE_DELETED")
            p.current?.let { demand(doc.download(it).source.expected_version != entry.version || doc.download(it).state == DownloadState.CORRUPT, "PIN_ALREADY_CURRENT") }
            val next = candidate(doc, p.peerId, p.pairingAttempt, p.rootId, entry)
            doc.replacePin(p.copy(candidate = next.id, observed = entry, tombstone = false, observedAt = now())).copy(downloads = doc.downloads + next)
        }; changed(); return after.download(after.pin(id).candidate ?: fail("PIN_NOT_FOUND"))
    }
    /** Independently re-decrypt and rehash every chunk before adopting the exact durable receipt.
     * Adopting an update retains the previous version as a reference; no silent byte replacement. */
    suspend fun adopt(id: String, downloads: DownloadEngine, confirmed: Boolean, guard: () -> Unit = {}) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        val candidate = repository.read()?.pin(id)?.candidate ?: fail("PIN_CANDIDATE_MISSING")
        val verified = downloads.verifyPrivate(candidate, guard)
        guard()
        repository.mutate { doc ->
            guard(); val p = doc.pin(id); val c = doc.download(p.candidate ?: fail("PIN_CANDIDATE_MISSING"))
            // Remote finish acknowledgment is independent of immutable local byte verification.
            // It may commit while verifyPrivate is re-reading chunks; all other fields, including
            // manifest, source, receipt, state and references, must still match exactly.
            demand(c.copy(remoteFinished = verified.remoteFinished) == verified && c.state == DownloadState.VERIFIED && c.receipt != null, "TRANSFER_NOT_VERIFIED")
            demand(p.previous.isEmpty() || p.current == null, "PIN_PREVIOUS_RETAINED")
            doc.replacePin(p.copy(current = c.id, candidate = null, previous = p.previous + listOfNotNull(p.current)))
        }; changed()
    }
    /** Release only a reviewed reference. The downloaded bytes remain in Transfers until separately
     * removed; source deletion/tombstones, failed refresh and unpin never erase an offline copy. */
    suspend fun release(id: String, downloadId: String, confirmed: Boolean) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { doc ->
            val p = doc.pin(id); demand(downloadId == p.candidate || downloadId in p.previous, "PIN_REFERENCED")
            val d = doc.download(downloadId); demand(d.state !in setOf(DownloadState.RUNNING, DownloadState.QUEUED), "TRANSFER_BUSY")
            doc.replacePin(p.copy(candidate = p.candidate?.takeUnless { it == downloadId }, previous = p.previous.filterNot { it == downloadId }))
        }; changed()
    }
    suspend fun unpin(id: String, confirmed: Boolean) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { doc -> val p = doc.pin(id); demand(p.references().none { doc.download(it).state in setOf(DownloadState.RUNNING, DownloadState.QUEUED) }, "TRANSFER_BUSY"); doc.copy(pins = doc.pins.filterNot { it.id == id }) }; changed()
    }
    suspend fun forgetMetadata(peerId: String, rootId: String, confirmed: Boolean) {
        demand(confirmed, "LOCAL_CONFIRMATION_REQUIRED")
        repository.mutate { doc -> demand(doc.pins.none { it.peerId == peerId && it.rootId == rootId }, "PIN_REFERENCED"); doc.copy(syncCaches = doc.syncCaches.filterNot { it.peerId == peerId && it.rootId == rootId }) }; changed()
    }
}
