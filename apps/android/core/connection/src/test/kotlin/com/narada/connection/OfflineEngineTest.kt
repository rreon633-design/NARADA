package com.narada.connection

import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class OfflineEngineTest {
    @Test fun metadataRefreshNeverDownloadsOrClaimsAByteCopy() = runBlocking<Unit> { val f = OfflineFixture(); val c = f.snapshot(); assertEquals(1, c.entries.size); assertNotNull(c.refreshedAt); assertTrue(f.memory.document.downloads.isEmpty()); assertTrue(f.memory.document.pins.isEmpty()); assertTrue(f.chunks.data.isEmpty()) }
    @Test fun pinAndExactSyncRequiredCandidateCommitTogether() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.pin(); val d = f.memory.document.download(p.candidate!!); assertTrue(d.syncRequired); assertEquals(f.entry.version, d.source.expected_version); assertNull(d.receipt); assertNull(p.current); assertEquals(0, f.prepared.size) }
    @Test fun completedDownloadStillNeedsExplicitFullVerificationBeforeAdoption() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.pin(); f.downloads.run(p.candidate!!); assertNull(f.memory.document.pin(p.id).current); f.engine.adopt(p.id, f.downloads, true); assertEquals(p.candidate, f.memory.document.pin(p.id).current); assertTrue(f.prepared.single().syncRequired) }
    @Test fun corruptedCandidateCannotBeAdoptedFromMetadataReceiptAlone() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.pin(); f.downloads.run(p.candidate!!); f.chunks.data[p.candidate!! to 0]!![0] = 0; expectFailure("LOCAL_COPY_CHANGED") { runBlocking { f.engine.adopt(p.id, f.downloads, true) } }; assertNull(f.memory.document.pin(p.id).current) }
    @Test fun sourceDeletionRetainsVerifiedOfflineCopy() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.saved(); val before = f.memory.document.download(p.current!!); f.deleted(); f.snapshot(); val current = f.memory.document.pin(p.id); assertTrue(current.tombstone); assertEquals(p.current, current.current); assertEquals(before, f.downloads.verifyPrivate(p.current!!)); assertTrue(f.chunks.data.isNotEmpty()) }
    @Test fun samePathNewObjectNeverSilentlyRebindsAnOldPin() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.saved(); val replacement = f.entry.copy(object_id = UUID.randomUUID().toString()); f.deleted(replacement); f.snapshot(); assertTrue(f.memory.document.pin(p.id).tombstone); assertEquals(f.entry.object_id, f.memory.document.pin(p.id).objectId); assertEquals(replacement.object_id, f.cache().entries.single().object_id) }
    @Test fun refreshCandidateDoesNotOverwriteCurrentAndAdoptionRetainsPrevious() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.saved(); val before = f.downloads.verifyPrivate(p.current!!); f.upsert(); f.snapshot(); val next = f.engine.refreshPin(p.id, true); assertNotEquals(before.id, next.id); assertEquals(before, f.memory.document.download(before.id)); assertEquals(before.id, f.memory.document.pin(p.id).current); f.downloads.run(next.id); f.engine.adopt(p.id, f.downloads, true); assertEquals(listOf(before.id), f.memory.document.pin(p.id).previous); assertEquals(next.id, f.memory.document.pin(p.id).current); assertEquals(before, f.downloads.verifyPrivate(before.id)) }
    @Test fun failedRefreshPreservesPreviousVerifiedVersionAndCandidateIntent() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.saved(); f.upsert(); f.snapshot(); val next = f.engine.refreshPin(p.id, true); f.beforeChunk = { _, _ -> fail("PEER_TIMEOUT") }; expectFailure("PEER_TIMEOUT") { runBlocking { f.downloads.run(next.id) } }; f.downloads.pause(next.id); assertEquals(p.current, f.memory.document.pin(p.id).current); assertEquals(next.id, f.memory.document.pin(p.id).candidate); f.downloads.verifyPrivate(p.current!!) }
    @Test fun referencedByteCopyCannotBeCleanedUntilUnpinnedAndExplicitlyRemoved() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.saved(); expectFailure("PIN_REFERENCED") { runBlocking { f.downloads.remove(p.current!!, true) } }; val count = f.chunks.data.size; f.engine.unpin(p.id, true); assertEquals(count, f.chunks.data.size); assertNotNull(f.memory.document.download(p.current!!).receipt); f.downloads.remove(p.current!!, true); assertTrue(f.chunks.data.isEmpty()) }
    @Test fun releasingACandidateOnlyReleasesReferenceNeverItsBytes() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.pin(); f.downloads.run(p.candidate!!); val count = f.chunks.data.size; f.engine.release(p.id, p.candidate!!, true); assertEquals(count, f.chunks.data.size); assertNull(f.memory.document.pin(p.id).candidate); assertNotNull(f.memory.document.download(p.candidate!!).receipt) }
    @Test fun anotherAdoptionCannotSilentlyEvictAnOlderRetainedVersion() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.saved(); f.upsert(); f.snapshot(); val second = f.engine.refreshPin(p.id, true); f.downloads.run(second.id); f.engine.adopt(p.id, f.downloads, true); f.upsert("c".repeat(64)); f.snapshot(); val third = f.engine.refreshPin(p.id, true); f.downloads.run(third.id); expectFailure("PIN_PREVIOUS_RETAINED") { runBlocking { f.engine.adopt(p.id, f.downloads, true) } }; assertEquals(second.id, f.memory.document.pin(p.id).current); assertEquals(listOf(p.current!!), f.memory.document.pin(p.id).previous) }
    @Test fun sharedFourDownloadQuotaNeverEvictsAPinnedVersion() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.saved(); val one = f.memory.document.download(p.current!!); f.memory.document = f.memory.document.copy(downloads = listOf(one) + (1..3).map { one.copy(id = UUID.randomUUID().toString()) }); val before = f.memory.document.downloads; f.upsert(); f.snapshot(); expectFailure("TRANSFER_LOCAL_QUOTA") { runBlocking { f.engine.refreshPin(p.id, true) } }; assertEquals(before, f.memory.document.downloads) }
    @Test fun completeMetadataAndPinsSurviveReopenWithoutNetworkWork() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.saved(); val count = f.requests.size; f.reopen(); assertEquals(count, f.requests.size); assertEquals(p, f.memory.document.pin(p.id)); assertNotNull(f.downloads.verifyPrivate(p.current!!).receipt) }
    @Test fun partialSnapshotKeepsPreviousCompletedViewAndResumesExactCursor() = runBlocking<Unit> {
        val f = OfflineFixture(); f.snapshot(); val prior = f.cache().entries; val token = UUID.randomUUID().toString(); val values = (0..15).map { f.entry.copy(object_id = UUID.randomUUID().toString(), name = "$it.bin", relative_path = "$it.bin") }; var first = true
        f.page = { t -> if (first) { first = false; SyncPage(f.root, "snapshot", values, emptyList(), token, null, 5) } else fail("PEER_TIMEOUT") }
        expectFailure("PEER_TIMEOUT") { runBlocking { f.engine.refresh(f.peer.id, f.root, true, true) } }; assertEquals(prior, f.cache().entries); assertEquals(token, f.cache().work!!.cursor)
        f.reopen(); f.page = { t -> assertEquals(token, t.work.cursor); SyncPage(f.root, "snapshot", emptyList(), emptyList(), null, UUID.randomUUID().toString(), 5) }; f.snapshot(); assertEquals(values, f.cache().entries); assertNull(f.cache().work)
    }
    @Test fun expiredCursorCausesOnlyOneFreshSnapshotAndNeverOwnerReindex() = runBlocking<Unit> { val f = OfflineFixture(); f.snapshot(); f.page = { t -> if (t.work.kind == "changes") fail("CURSOR_EXPIRED") else SyncPage(f.root, "snapshot", f.entries, emptyList(), null, UUID.randomUUID().toString(), 2) }; f.snapshot(); assertEquals(listOf("snapshot", "changes", "snapshot"), f.requests.map { it.work.kind }); assertEquals(2L, f.cache().fence) }
    @Test fun repeatedExpiryIsBoundedAndPreviousMetadataIsRetained() = runBlocking<Unit> { val f = OfflineFixture(); f.snapshot(); val prior = f.cache().entries; f.page = { fail("CURSOR_EXPIRED") }; expectFailure("CURSOR_EXPIRED") { runBlocking { f.snapshot() } }; assertEquals(3, f.requests.size); assertEquals(prior, f.cache().entries) }
    @Test fun authorityChangedWhilePageWasInFlightCannotPublishIt() = runBlocking<Unit> { val f = OfflineFixture(); f.beforePage = { val p = f.memory.document.peer(f.peer.id); f.memory.document = f.memory.document.replace(p.copy(localEpoch = p.localEpoch + 1)) }; expectFailure("SYNC_SCOPE_CHANGED") { runBlocking { f.snapshot() } }; assertTrue(f.cache().entries.isEmpty()); assertNull(f.cache().refreshedAt) }
    @Test fun failedSnapshotCommitNeverAdvancesCursorOrAvailability() = runBlocking<Unit> { val f = OfflineFixture(); f.beforeWrite = { d -> if (d.syncCaches.any { it.refreshedAt != null }) fail("STORAGE_UNAVAILABLE") }; expectFailure("STORAGE_UNAVAILABLE") { runBlocking { f.snapshot() } }; assertNull(f.cache().cursor); assertNull(f.cache().refreshedAt); assertTrue(f.memory.document.downloads.isEmpty()) }
    @Test fun syncReadWithdrawalRefusesNewPinButDoesNotEraseOldCopy() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.saved(); val peer = f.memory.document.peer(f.peer.id); f.memory.document = f.memory.document.replace(peer.copy(remotePermissions = Permissions(roots = listOf(RootGrant(f.root, listOf("browse", "read")))), remoteRevision = 2)); expectFailure("SYNC_ACCESS_DENIED") { runBlocking { f.snapshot() } }; f.downloads.verifyPrivate(p.current!!); assertEquals(p.current, f.memory.document.pin(p.id).current) }
    @Test fun metadataCleanupIsReferenceAwareAndNeverRemovesCopies() = runBlocking<Unit> { val f = OfflineFixture(); val p = f.saved(); expectFailure("PIN_REFERENCED") { runBlocking { f.engine.forgetMetadata(f.peer.id, f.root, true) } }; f.engine.unpin(p.id, true); f.engine.forgetMetadata(f.peer.id, f.root, true); assertTrue(f.memory.document.syncCaches.isEmpty()); assertNotNull(f.downloads.verifyPrivate(p.current!!).receipt) }
    @Test fun remoteFinishAckRacingVerificationDoesNotBlockAdoptionOrReplaceBytes() = runBlocking<Unit> {
        val f = OfflineFixture(); val p = f.pin(); f.downloads.run(p.candidate!!); val before = f.memory.document.download(p.candidate)
        val storage = object : TransferChunks by f.chunks { override fun read(storeId: String, record: DownloadRecord, index: Int): ByteArray? { val bytes = f.chunks.read(storeId, record, index); f.memory.document = f.memory.document.replaceDownload(f.memory.document.download(record.id).copy(remoteFinished = true)); return bytes } }
        f.engine.adopt(p.id, DownloadEngine(f.repository, f.transfers, storage), true)
        assertEquals(p.candidate, f.memory.document.pin(p.id).current); assertEquals(before.receipt, f.memory.document.download(p.candidate).receipt); assertTrue(f.memory.document.download(p.candidate).remoteFinished)
    }
    @Test fun changedLocalReceiptDuringVerificationStillPreventsAdoption() = runBlocking<Unit> {
        val f = OfflineFixture(); val p = f.pin(); f.downloads.run(p.candidate!!)
        val storage = object : TransferChunks by f.chunks { override fun read(storeId: String, record: DownloadRecord, index: Int): ByteArray? { val bytes = f.chunks.read(storeId, record, index); val current = f.memory.document.download(record.id); f.memory.document = f.memory.document.replaceDownload(current.copy(receipt = current.receipt!!.copy(verifiedAt = current.receipt.verifiedAt + 1))); return bytes } }
        expectFailure("TRANSFER_NOT_VERIFIED") { runBlocking { f.engine.adopt(p.id, DownloadEngine(f.repository, f.transfers, storage), true) } }; assertNull(f.memory.document.pin(p.id).current)
    }

    @Test fun lateOldFailureCannotMarkNewWorkFailed() = runBlocking<Unit> {
        val f = OfflineFixture(); f.snapshot(); var next: SyncCache? = null
        f.beforePage = { t -> next = f.cache().copy(work = t.work.copy(id = UUID.randomUUID().toString()), error = null); f.memory.document = f.memory.document.replaceSync(next!!) }
        f.page = { fail("PEER_TIMEOUT") }
        expectFailure("PEER_TIMEOUT") { runBlocking { f.snapshot() } }; assertEquals(next, f.cache())
    }
    @Test fun lateOldExpiryCannotRestartOrReplaceNewWork() = runBlocking<Unit> {
        val f = OfflineFixture(); f.snapshot(); var next: SyncCache? = null
        f.beforePage = { t -> next = f.cache().copy(work = t.work.copy(id = UUID.randomUUID().toString()), error = null); f.memory.document = f.memory.document.replaceSync(next!!) }
        f.page = { fail("CURSOR_EXPIRED") }
        expectFailure("SYNC_STOPPED") { runBlocking { f.snapshot() } }; assertEquals(next, f.cache()); assertEquals(2, f.requests.size)
    }
    @Test fun forgottenCacheIsNotRecreatedByLateExpiryOrFailure() = runBlocking<Unit> {
        for (error in listOf("CURSOR_EXPIRED", "PEER_TIMEOUT")) {
            val f = OfflineFixture(); f.snapshot()
            f.beforePage = { runBlocking { f.engine.forgetMetadata(f.peer.id, f.root, true) } }; f.page = { fail(error) }
            expectFailure(if (error == "CURSOR_EXPIRED") "SYNC_STOPPED" else error) { runBlocking { f.snapshot() } }
            assertTrue(f.memory.document.syncCaches.isEmpty()); assertEquals(2, f.requests.size)
        }
    }
    @Test fun lateSuccessCannotReplaceACompletedNewerSnapshot() = runBlocking<Unit> {
        val f = OfflineFixture(); f.snapshot(); var next: SyncCache? = null
        f.beforePage = { next = f.cache().copy(work = null, cursor = UUID.randomUUID().toString(), refreshedAt = f.clock + 1); f.memory.document = f.memory.document.replaceSync(next!!) }
        expectFailure("SYNC_STOPPED") { runBlocking { f.snapshot() } }; assertEquals(next, f.cache())
    }
    @Test fun sixteenPageAdmissionLimitKeepsCommittedViewAndExactPartialWork() = runBlocking<Unit> {
        val f = OfflineFixture(); f.snapshot(); val before = f.cache().entries
        f.page = { t -> val cursor = UUID.randomUUID().toString(); val seq = t.work.fence!! + 1; SyncPage(f.root, "changes", emptyList(), listOf(SyncEvent(seq, "upsert", f.entry.object_id, f.entry.copy(version = "d".repeat(64)))), cursor, cursor, seq) }
        expectFailure("SYNC_PAGE_LIMIT") { runBlocking { f.snapshot() } }
        assertEquals(17, f.requests.size); assertEquals(before, f.cache().entries); assertEquals("SYNC_PAGE_LIMIT", f.cache().error); assertEquals(17L, f.cache().work!!.fence); assertEquals("d".repeat(64), f.cache().work!!.entries.single().version)
    }

    @Test fun pinReservationIncludesAttachmentBytesAndNeverEvictsRetainedJobs() = runBlocking<Unit> {
        val f = OfflineFixture(); f.entries = listOf(f.entry.copy(size = (16L * 1024 * 1024).toString())); f.snapshot()
        val downloads = listOf(64L, 24L).map { size -> DownloadRecord(UUID.randomUUID().toString(), f.peer.id, f.peer.attemptId, TransferSource(f.root, f.entry.object_id, f.entry.version), "reserved.bin", size * 1024 * 1024, f.clock) }
        val attachments = (1..4).map { AttachmentRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), f.peer.id, f.peer.attemptId, ChatDirection.OUTGOING, f.clock, state = AttachmentState.PICKER_PENDING) }
        f.memory.document = f.memory.document.copy(downloads = downloads, attachments = attachments); f.memory.document.validate()
        expectFailure("TRANSFER_LOCAL_QUOTA") { runBlocking { f.engine.pin(f.peer.id, f.root, f.entry.object_id, true) } }
        assertEquals(downloads, f.memory.document.downloads); assertEquals(attachments, f.memory.document.attachments); assertTrue(f.memory.document.pins.isEmpty())
    }

}
