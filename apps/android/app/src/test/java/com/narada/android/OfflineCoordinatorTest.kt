package com.narada.android

import com.narada.connection.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fake peer/storage for foreground ownership and private-projection behavior only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class OfflineCoordinatorTest {
    private lateinit var f: ContentCoordinatorFixture
    @Before fun setup() = runBlocking<Unit> { f = ContentCoordinatorFixture(); f.setup(); f.offlineView() }
    @After fun close() = runBlocking<Unit> { if (::f.isInitialized) f.close() }
    private suspend fun metadata() { f.offline.refreshMetadata(); f.offlineState { it.freshAt != null && !it.busy } }
    private suspend fun pin(): OfflinePin { metadata(); f.offline.pin(f.syncEntry.object_id, true); f.offlineState { it.pins.singleOrNull()?.candidateState == DownloadState.VERIFIED }; return f.repo.doc.pins.single() }
    @Test fun openingOfflineDoesNotScheduleMetadataOrDownloads() = runBlocking<Unit> { assertEquals(0, f.syncCalls.get()); assertEquals(0, f.prepares.get()); assertTrue(f.offline.state.value.entries.isEmpty()); assertTrue(f.offline.state.value.pins.isEmpty()) }
    @Test fun refreshedMetadataDoesNotClaimVerifiedAvailability() = runBlocking<Unit> { metadata(); val s = f.offline.state.value; assertEquals(listOf(f.syncEntry), s.entries); assertTrue(s.scopeCurrent); assertTrue(s.pins.isEmpty()); assertEquals(0, f.prepares.get()); assertTrue(f.repo.doc.downloads.isEmpty()) }
    @Test fun candidateProgressUpdatesProjectionButAdoptionStillNeedsConsent() = runBlocking<Unit> { val p = pin(); assertFalse(f.offline.state.value.pins.single().available); assertNull(f.repo.doc.pin(p.id).current); assertTrue(f.repo.doc.download(p.candidate!!).syncRequired); f.offline.adopt(p.id, false); f.offlineState { it.error == "LOCAL_CONFIRMATION_REQUIRED" }; assertNull(f.repo.doc.pin(p.id).current); f.offline.adopt(p.id, true); f.offlineState { it.pins.single().available }; assertEquals(p.candidate, f.repo.doc.pin(p.id).current) }
    @Test fun failedRefreshRetainsCompletedMetadataAndSavedPin() = runBlocking<Unit> { val p = pin(); f.offline.adopt(p.id, true); f.offlineState { it.pins.single().available }; val before = f.repo.doc.downloads.single().receipt; f.beforePage = { com.narada.connection.fail("PEER_TIMEOUT") }; f.offline.refreshMetadata(); f.offlineState { it.metadataError == "PEER_TIMEOUT" && !it.busy }; assertEquals(listOf(f.syncEntry), f.offline.state.value.entries); assertTrue(f.offline.state.value.pins.single().available); assertEquals(before, f.repo.doc.downloads.single().receipt) }
    @Test fun backgroundDiscardsLatePageAndReturningDoesNotAutoRefresh() = runBlocking<Unit> { val entered = CompletableDeferred<Unit>(); f.beforePage = { entered.complete(Unit); withContext(NonCancellable) { delay(200) } }; f.offline.refreshMetadata(); entered.await(); f.connections.foregroundChanged(false); f.offlineState { it.entries.isEmpty() && it.access == null && !it.busy }; assertNull(f.repo.doc.syncCaches.single().refreshedAt); val calls = f.syncCalls.get(); f.beforePage = {}; f.connections.foregroundChanged(true); withTimeout(5000) { f.connections.state.first { it.foreground } }; delay(60); assertEquals(calls, f.syncCalls.get()); assertTrue(f.offline.state.value.entries.isEmpty()) }
    @Test fun leavingTabClearsNamesPinsAndFreshnessImmediately() = runBlocking<Unit> { metadata(); f.offline.visible(false); val s = f.offline.state.value; assertTrue(s.entries.isEmpty()); assertTrue(s.pins.isEmpty()); assertTrue(s.roots.isEmpty()); assertNull(s.freshAt); assertFalse(s.scopeCurrent); assertNotNull(f.repo.doc.syncCaches.single().refreshedAt) }
    @Test fun publicConnectionProjectionNeverContainsCachedPathsOrPinReferences() = runBlocking<Unit> { val p = pin(); f.connections.reload(); withTimeout(5000) { f.connections.state.first { it.connectedPeer == f.peer.id } }; val public = f.connections.state.value.document!!; assertTrue(public.syncCaches.isEmpty()); assertTrue(public.pins.isEmpty()); assertTrue(public.attachments.isEmpty()); assertNull(public.chat); assertFalse(Wire.encode(public, Wire.MAX_STORE).decodeToString().contains(p.id)) }
    @Test fun unpinReleasesReferencesButDoesNotDeletePrivateBytes() = runBlocking<Unit> { val p = pin(); f.offline.adopt(p.id, true); f.offlineState { it.pins.single().available }; val count = f.downloadChunks.data.size; f.offline.unpin(p.id, true); f.offlineState { it.pins.isEmpty() }; assertEquals(count, f.downloadChunks.data.size); assertNotNull(f.repo.doc.download(p.candidate!!).receipt); assertEquals(1, f.prepares.get()) }
}
