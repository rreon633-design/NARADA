package com.narada.connection

import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Actual current Linux Rust sync/prepare_sync endpoints; owner reindex is fixture-only IPC. */
class OfflineDesktopInteropTest {
    private lateinit var f: ContentDesktopFixture
    @Before fun setup() = runBlocking<Unit> { f = ContentDesktopFixture(); f.setup(many = true) }
    @After fun close() { if (::f.isInitialized) f.close() }
    @Test fun realPaginatedSnapshotContainsAuthorizedMetadataButNoByteCopies() = runBlocking<Unit> { val c = f.cache(); assertEquals(22, c.entries.size); assertEquals(2, f.pages.size); assertEquals(listOf("snapshot", "snapshot"), f.pages.map { it.work.kind }); assertNotNull(c.cursor); assertNull(c.work); assertTrue(f.store.document.downloads.isEmpty()); assertTrue(f.prepared.isEmpty()) }
    @Test fun realPrepareSyncDownloadAndExplicitAdoptionProducePersistentOfflinePin() = runBlocking<Unit> { val p = f.saved(); val d = f.downloads.verifyPrivate(p.current!!); assertTrue(f.prepared.single().syncRequired); assertEquals(sha256(f.bytes), d.receipt!!.sha256); f.restartBoth(); assertEquals(p, f.store.document.pin(p.id)); assertEquals(d.receipt, f.downloads.verifyPrivate(p.current).receipt); assertEquals(1, f.prepared.size) }
    @Test fun realChangesStageANewVersionWithoutReplacingVerifiedCurrent() = runBlocking<Unit> {
        val p = f.saved(); val first = f.downloads.verifyPrivate(p.current!!); File(f.share, "source.bin").writeBytes(f.bytes.clone().apply { this[0] = 42 }); f.reindex(); val c = f.cache(); assertEquals("changes", f.pages.last().work.kind); assertNotEquals(first.source.expected_version, c.entries.single { it.name == "source.bin" }.version)
        val next = f.offline.refreshPin(p.id, true); assertEquals(first.id, f.store.document.pin(p.id).current); assertNull(next.receipt); f.downloads.run(next.id); f.offline.adopt(p.id, f.downloads, true)
        assertEquals(listOf(first.id), f.store.document.pin(p.id).previous); assertEquals(first.receipt, f.downloads.verifyPrivate(first.id).receipt); assertNotEquals(first.receipt!!.sha256, f.downloads.verifyPrivate(next.id).receipt!!.sha256)
    }
    @Test fun realSourceTombstoneNeverErasesTheSavedCopy() = runBlocking<Unit> { val p = f.saved(); assertTrue(File(f.share, "source.bin").delete()); f.reindex(); f.cache(); val pin = f.store.document.pin(p.id); assertTrue(pin.tombstone); assertEquals(p.current, pin.current); assertEquals(sha256(f.bytes), f.downloads.verifyPrivate(pin.current!!).receipt!!.sha256); expectFailure("PIN_SOURCE_DELETED") { runBlocking { f.offline.refreshPin(p.id, true) } } }
    @Test fun failedVersionPrepareLeavesPreviouslyPinnedBytesAndCandidateForReview() = runBlocking<Unit> { val p = f.saved(); File(f.share, "source.bin").writeText("second observed source"); f.reindex(); f.cache(); val candidate = f.offline.refreshPin(p.id, true); File(f.share, "source.bin").writeText("third unindexed source"); expectFailure("STALE_VERSION") { runBlocking { f.downloads.run(candidate.id) } }; f.downloads.pause(candidate.id); assertEquals(p.current, f.store.document.pin(p.id).current); assertNull(f.store.document.download(candidate.id).receipt); assertEquals(sha256(f.bytes), f.downloads.verifyPrivate(p.current!!).receipt!!.sha256) }
    @Test fun serverRestartExpiresOldCursorAndNewEndpointScopeStartsFreshSnapshot() = runBlocking<Unit> {
        f.afterPage = { _, r -> if (r.page.next_cursor == null) fail("PEER_TIMEOUT") }
        expectFailure("PEER_TIMEOUT") { runBlocking { f.cache() } }; val staged = f.store.document.syncCache(f.peerId, f.rootId); assertEquals(16, staged.work!!.entries.size); assertTrue(staged.entries.isEmpty())
        f.restartBoth(); assertEquals(2, f.pages.size)
        expectFailure("CURSOR_EXPIRED") { runBlocking { f.pairing.withActiveSession(f.peerId) { _, s -> s.post<SyncWire, SyncPage>(SyncRules.PATH, SyncWire(request = SyncRequest("snapshot", f.rootId, staged.work!!.cursor))) } } }
        f.afterPage = { _, _ -> }; val done = f.cache(); assertEquals(22, done.entries.size); assertEquals(4, f.pages.size); assertNull(f.pages[2].work.cursor); assertNotEquals(staged.work!!.scope.localEpoch, f.pages[2].work.scope.localEpoch); assertNotNull(done.refreshedAt)
    }
    @Test fun stableSnapshotNeverMixesConcurrentReindexAndThenChangesCatchUp() = runBlocking<Unit> { var once = false; f.afterPage = { _, r -> if (!once && r.page.next_cursor != null) { once = true; File(f.share, "late.txt").writeText("new external source"); f.reindex() } }; val stable = f.cache(); assertEquals(22, stable.entries.size); assertEquals(2, f.pages.size); assertTrue(stable.entries.none { it.name == "late.txt" }); assertNull(stable.work); val changed = f.cache(); assertEquals(23, changed.entries.size); assertEquals(1, changed.entries.count { it.name == "late.txt" }); assertEquals("changes", f.pages.last().work.kind); assertTrue(changed.fence > stable.fence) }
    @Test fun actualSyncGrantWithdrawalDeniesRefreshButNotOfflineVerification() = runBlocking<Unit> { val p = f.saved(); f.permissions(f.grants(sync = false)); expectFailure("SYNC_ACCESS_DENIED") { runBlocking { f.cache() } }; assertEquals(sha256(f.bytes), f.downloads.verifyPrivate(p.current!!).receipt!!.sha256); assertEquals(p.current, f.store.document.pin(p.id).current) }
    @Test fun actualPostPageGrantChangeCannotPublishFormerAuthorityMetadata() = runBlocking<Unit> { f.afterPage = { _, _ -> f.permissions(f.grants(sync = false)); f.pairing.withActiveSession(f.peerId) { p, s -> f.pairing.health(p, s) } }; expectFailure("SYNC_ACCESS_DENIED") { runBlocking { f.cache() } }; val c = f.store.document.syncCache(f.peerId, f.rootId); assertTrue(c.entries.isEmpty()); assertNull(c.refreshedAt); assertTrue(f.store.document.pins.isEmpty()) }
    @Test fun referenceAwareRemovalRequiresSeparateReviewedUnpinAndByteCleanup() = runBlocking<Unit> { val p = f.saved(); expectFailure("PIN_REFERENCED") { runBlocking { f.downloads.remove(p.current!!, true) } }; f.offline.unpin(p.id, true); assertEquals(sha256(f.bytes), f.downloads.verifyPrivate(p.current!!).receipt!!.sha256); f.downloads.remove(p.current, true); assertTrue(f.store.document.downloads.isEmpty()); assertEquals(22, f.store.document.syncCache(f.peerId, f.rootId).entries.size) }
}
