package com.narada.connection

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

private class SyncPeerFixture {
    val f = OfflineFixture(); val client = SyncClient(f.repository, f.peerFixture.engine)
    var health = Health(Hello(), f.peer.id, f.memory.document.peer(f.peer.id).remotePermissions, 1, 1, chatOperations)
    var healthCount = 0; var requests = 0; var onHealth: (Int) -> Unit = {}; var error: String? = null
    var transform: (SyncPage) -> SyncPage = { it }; val sent = mutableListOf<SyncWire>()
    init { f.beforePage = { fail("PEER_TIMEOUT") }; expectFailure("PEER_TIMEOUT") { runBlocking { f.snapshot() } }; f.beforePage = {} }
    fun ticket() = SyncTicket(f.peer.id, f.root, f.cache().work!!)
    val session = object : PeerSession {
        override val certificate = f.peerFixture.host.certificate.encoded
        override fun close() {}
        override fun request(path: String, body: ByteArray): ByteArray {
            if (path == "/api/v1/health") { healthCount++; onHealth(healthCount); return Wire.encode(Envelope(true, health, null)) }
            assertEquals(SyncRules.PATH, path); requests++; sent += Wire.decode<SyncWire>(body)
            error?.let { return Wire.encode(Envelope<JsonElement>(false, null, RemoteError(it, "PRIVATE remote path must not appear"))) }
            val page = SyncPage(f.root, "snapshot", f.entries, emptyList(), null, UUID.randomUUID().toString(), 1)
            return Wire.encode(Envelope(true, transform(page), null), FileRules.MAX_RESPONSE)
        }
    }
    suspend fun get(ticket: SyncTicket = ticket(), guard: () -> Unit = {}) = client.page(f.memory.document.peer(f.peer.id), session, ticket, guard)
}
class SyncClientTest {
    @Test fun exactReadOnlySnapshotHasPreAndPostAuthenticatedHealth() = runBlocking<Unit> { val f = SyncPeerFixture(); val r = f.get(); assertEquals(2, f.healthCount); assertEquals(1, f.requests); assertEquals(SyncRequest("snapshot", f.f.root, null), f.sent.single().request); assertEquals(f.f.entries, r.page.entries); assertTrue(f.f.memory.document.downloads.isEmpty()) }
    @Test fun syncGrantWithdrawalStopsBeforeMetadataContent() = runBlocking<Unit> { val f = SyncPeerFixture(); f.health = f.health.copy(permissions = Permissions(roots = listOf(RootGrant(f.f.root, listOf("browse", "read")))), grant_revision = 2, session_epoch = 2); expectFailure("SYNC_ACCESS_DENIED") { runBlocking { f.get() } }; assertEquals(0, f.requests) }
    @Test fun postHealthRevisionChangeDiscardsValidMetadataPage() = runBlocking<Unit> { val f = SyncPeerFixture(); f.onHealth = { if (it == 2) f.health = f.health.copy(grant_revision = 2, session_epoch = 2) }; expectFailure("SYNC_SCOPE_CHANGED") { runBlocking { f.get() } }; assertEquals(1, f.requests); assertTrue(f.f.cache().entries.isEmpty()) }
    @Test fun persistedWorkIdIsRequiredBeforeAnyWireRequest() = runBlocking<Unit> { val f = SyncPeerFixture(); val t = f.ticket(); expectFailure("SYNC_SCOPE_CHANGED") { runBlocking { f.get(t.copy(work = t.work.copy(id = UUID.randomUUID().toString()))) } }; assertEquals(0, f.healthCount); assertEquals(0, f.requests) }
    @Test fun substitutedPeerCannotReuseAnOtherwiseValidCursor() = runBlocking<Unit> { val f = SyncPeerFixture(); expectFailure("SYNC_SCOPE_CHANGED") { runBlocking { f.get(f.ticket().copy(peerId = "a".repeat(64))) } }; assertEquals(0, f.healthCount) }
    @Test fun foregroundFenceRunsBeforeAnySocketOperation() = runBlocking<Unit> { val f = SyncPeerFixture(); expectFailure("SYNC_STOPPED") { runBlocking { f.get(guard = { fail("SYNC_STOPPED") }) } }; assertEquals(0, f.healthCount); assertEquals(0, f.requests) }
    @Test fun substitutedRootInReplyNeverCommits() = runBlocking<Unit> { val f = SyncPeerFixture(); f.transform = { it.copy(root_id = UUID.randomUUID().toString()) }; expectFailure("INVALID_SYNC_RESPONSE") { runBlocking { f.get() } }; assertNull(f.f.cache().refreshedAt) }
    @Test fun repeatedCursorCannotCreateAnUnboundedLoop() = runBlocking<Unit> { val f = SyncPeerFixture(); val t = f.ticket(); val token = UUID.randomUUID().toString(); f.f.memory.document = f.f.memory.document.replaceSync(f.f.cache().copy(work = t.work.copy(cursor = token))); f.transform = { it.copy(next_cursor = token, changes_cursor = null) }; expectFailure("INVALID_SYNC_RESPONSE") { runBlocking { f.get() } }; assertEquals(1, f.requests) }
    @Test fun remoteStorageFailureDoesNotImpersonatePhoneStorageFailure() = runBlocking<Unit> { val f = SyncPeerFixture(); f.error = "STORAGE_UNAVAILABLE"; expectFailure("SYNC_REMOTE_STORAGE") { runBlocking { f.get() } }; assertNotNull(f.f.memory.read()) }
    @Test fun expiryIsAnExplicitTypedErrorNotRemoteProse() = runBlocking<Unit> { val f = SyncPeerFixture(); f.error = "CURSOR_EXPIRED"; expectFailure("CURSOR_EXPIRED") { runBlocking { f.get() } } }
    @Test fun unknownRemoteErrorsAreReducedToAProtocolCode() = runBlocking<Unit> { val f = SyncPeerFixture(); f.error = "PRIVATE_PATH"; expectFailure("PEER_REJECTED") { runBlocking { f.get() } } }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun syncAndAttachmentRequestsUseTheSameContentAdmissionClock() = runTest {
        val starts = mutableListOf<Long>(); val admission = ContentAdmission { testScheduler.currentTime }; val f = SyncPeerFixture(); val a = AttachmentFixture(byteArrayOf(1))
        val record = a.engine.prepareImport(a.chat.peerId, "", true)
        val sync = PacedSyncGateway(object : SyncGateway { override suspend fun page(ticket: SyncTicket): SyncResult { starts += testScheduler.currentTime; return f.get(ticket) }; override fun cancel() {} }, admission)
        val attachments = PacedAttachmentsGateway(object : AttachmentsGateway by a.gateway { override suspend fun capabilities(record: AttachmentRecord): AttachmentResult<UploadCapabilities> { starts += testScheduler.currentTime; return a.gateway.capabilities(record) } }, admission)
        sync.page(f.ticket()); attachments.capabilities(record); sync.page(f.ticket()); assertEquals(listOf(0L, 300L, 600L), starts)
    }
}
