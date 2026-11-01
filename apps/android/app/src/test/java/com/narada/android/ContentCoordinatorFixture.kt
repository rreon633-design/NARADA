package com.narada.android

import com.narada.android.attachments.*
import com.narada.android.chat.*
import com.narada.android.connection.*
import com.narada.android.offline.*
import com.narada.android.transfers.*
import com.narada.connection.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Deliberately FAKE crypto/storage/peer for ownership/lifecycle tests. No cryptographic claims.
 * Actual host JNI, sealed storage and actual-agent campaigns are separate classes. */
internal class ContentCoordinatorFixture {
    class Repository : TrustRepository {
        val key = AndroidTestIdentities.create(); private val mutex = Mutex(); @Volatile var failWrite = false
        @Volatile var doc = TrustDocument(storeId = UUID.randomUUID().toString(), deviceId = key.deviceId, fingerprint = key.fingerprint, certificate = Base64.getEncoder().encodeToString(key.certificate.encoded), enabled = true)
        override suspend fun read() = doc
        override suspend fun initialize() = doc
        override suspend fun identity() = key
        override suspend fun mutate(change: (TrustDocument) -> TrustDocument) = mutex.withLock { val next = change(doc); next.validate(); doc.chat?.requireContinuation(next.chat); requireAttachmentContinuation(doc, next); if (failWrite) fail("STORAGE_UNAVAILABLE"); doc = next.copy(generation = doc.generation + 1); doc }
    }
    class FakeOlm : OlmCryptography {
        private fun key(b: Int, n: Int = 32) = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(n) { b.toByte() })
        override fun info() = OlmInfo(2, "0.11.1", "olm.v1", 64, false)
        override fun create() = OlmAccount("TEST-ONLY-ACCOUNT", key(1), key(2))
        override fun inspect(account: String) = OlmPublic(key(1), key(2))
        override fun inspectSession(session: ChatSession) {}
        override fun bundle(account: String, localId: String, peerId: String) = OlmBundle(account, ChatBundle(transport_id = localId, recipient = peerId, curve = key(1), ed = key(2), one_time = key(3), signature = key(4, 64)))
        override fun verify(bundle: ChatBundle, localId: String, peerId: String) = bundle.validate(peerId, localId)
        override fun encrypt(account: String, ours: ChatBundle, remote: ChatBundle, session: ChatSession?, body: ChatBody): OlmEncrypted = error("No crypto test")
        override fun decrypt(account: String, ours: ChatBundle, remote: ChatBundle, sessions: List<ChatSession>, envelope: ChatEnvelope): OlmDecrypted = error("No crypto test")
    }
    class FakeAge : AgeCryptography {
        override fun info() {}
        override fun validateIdentity(secret: AttachmentSecret) = secret.validate()
        override fun encrypt(name: String, plain: ByteArray): AgeEncrypted { val cipher = byteArrayOf(42) + plain; return AgeEncrypted(AttachmentSecret(BlobDescriptor(blob_id = UUID.randomUUID().toString(), size = cipher.size.toLong(), sha256 = sha256(cipher), hashes = listOf(sha256(cipher))), name, plain.size.toLong(), sha256(plain), "AGE-SECRET-KEY-1" + "Q".repeat(59)), cipher) }
        override fun decrypt(secret: AttachmentSecret, ciphertext: ByteArray): ByteArray { demand(sha256(ciphertext) == secret.descriptor.sha256, "HASH_MISMATCH"); return ciphertext.copyOfRange(1, ciphertext.size).also { demand(sha256(it) == secret.plaintext_sha256, "HASH_MISMATCH") } }
    }
    val foundation = TestFixture(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val repo = Repository(); val olm = FakeOlm(); val age = FakeAge()
    val peer = TransferTestData.peer().copy(remotePermissions = Permissions(chat = true, attachment = true, roots = listOf(RootGrant(TransferTestData.root, listOf("browse", "read", "sync_read")))), remoteOperations = listOf("health", "permissions", "files.v1", "transfers.v1", "chat.olm.v1", "attachments.age.v1", "sync.v1"))
    lateinit var connections: ConnectionsCoordinator; lateinit var chat: ChatCoordinator; lateinit var attachments: AttachmentsCoordinator; lateinit var transfers: TransfersCoordinator; lateinit var offline: OfflineCoordinator
    val attachmentChunks = TestTransferChunks(); val downloadChunks = TestTransferChunks(); val providerIo = BoundedProviderIo(1200)
    val probes = AtomicInteger(); val imports = AtomicInteger(); val exports = AtomicInteger(); val writes = AtomicInteger(); val creates = AtomicInteger(); val cancelled = AtomicInteger(); val syncCalls = AtomicInteger(); val prepares = AtomicInteger()
    var failImportFactory = false; var failExportFactory = false; @Volatile var holdOpen = false; @Volatile var holdClose = false
    val opened = CountDownLatch(1); val releaseOpen = CountDownLatch(1); val closing = CountDownLatch(1); val releaseClose = CountDownLatch(1)
    var beforePage: suspend (SyncTicket) -> Unit = {}; var beforeChunk: suspend (Int) -> Unit = {}
    val plain = "PRIVATE_ATTACHMENT_NAME_FIXTURE_CONTENT".toByteArray(); val exported = ByteArrayOutputStream()
    val attachmentGateway = object : AttachmentsGateway {
        override suspend fun capabilities(record: AttachmentRecord): AttachmentResult<UploadCapabilities> { probes.incrementAndGet(); return AttachmentResult(UploadCapabilities(1, AttachmentRules.UPLOAD, "age.x25519.v1", TransferRules.CHUNK, AttachmentRules.MAX_PLAIN, AttachmentRules.MAX_CIPHER, 33), ChatScope(repo.doc.peer(peer.id))) }
        override suspend fun begin(record: AttachmentRecord): AttachmentResult<UploadStatus> = error("No automatic upload allowed")
        override suspend fun status(record: AttachmentRecord): AttachmentResult<UploadStatus> = error("No automatic upload allowed")
        override suspend fun finish(record: AttachmentRecord): AttachmentResult<UploadStatus> = error("No automatic upload allowed")
        override suspend fun upload(record: AttachmentRecord, index: Int, bytes: ByteArray): AttachmentResult<Unit> = error("No automatic upload allowed")
        override suspend fun download(record: AttachmentRecord, index: Int): AttachmentResult<ByteArray> = error("No automatic download allowed")
        override fun cancel() {}
    }
    val syncEntry = SyncEntry(TransferTestData.objectId, null, "ordinary.bin", "ordinary.bin", "file", TransferTestData.bytes.size.toString(), TransferTestData.source.expected_version)
    val syncGateway = object : SyncGateway {
        override suspend fun page(ticket: SyncTicket): SyncResult { syncCalls.incrementAndGet(); beforePage(ticket); return SyncResult(SyncPage(ticket.rootId, ticket.work.kind, if (ticket.work.kind == "snapshot") listOf(syncEntry) else emptyList(), emptyList(), null, UUID.randomUUID().toString(), ticket.work.fence ?: 1), ticket.work.scope) }
        override fun cancel() {}
    }
    val transferGateway = object : TransfersGateway {
        override suspend fun prepare(record: DownloadRecord): ManifestBundle { prepares.incrementAndGet(); return TransferTestData.bundle(record.name) }
        override suspend fun chunk(record: DownloadRecord, index: Int): ByteArray { beforeChunk(index); return TransferTestData.bytes.copyOfRange(index * TransferRules.CHUNK, minOf((index + 1) * TransferRules.CHUNK, TransferTestData.bytes.size)) }
        override suspend fun finish(record: DownloadRecord) {}
        override fun cancel() {}
    }
    private val importFactory = ImportProviderFactory { _, _ -> imports.incrementAndGet(); if (failImportFactory) fail("PROVIDER_PERMISSION_LOST"); object : ImportDocument {
        override fun open(): Pair<String, java.io.InputStream> { opened.countDown(); if (holdOpen) while (releaseOpen.count > 0) try { releaseOpen.await() } catch (_: InterruptedException) {} ; return "PRIVATE_ATTACHMENT.bin" to ByteArrayInputStream(plain) }
        override fun cancel() { cancelled.incrementAndGet() }
        override fun close() { closing.countDown(); if (holdClose) while (releaseClose.count > 0) try { releaseClose.await() } catch (_: InterruptedException) {} }
    } }
    val exportFactory = ExportProviderFactory { _, _ -> exports.incrementAndGet(); if (failExportFactory) fail("PROVIDER_PERMISSION_LOST"); object : NewDocumentProvider {
        override fun preflight(tree: String, name: String) {}
        override fun create(tree: String, name: String): String { creates.incrementAndGet(); return "content://fixture.documents/document/new" }
        override fun writer(document: String): java.io.OutputStream { writes.incrementAndGet(); return exported }
        override fun reader(document: String) = ByteArrayInputStream(exported.toByteArray())
        override fun cancel() {}
        override fun close() {}
    } }
    suspend fun setup() {
        repo.doc = repo.doc.copy(peers = listOf(peer), preferredPeer = peer.id); ChatEngine(repo, olm, age).enablePeer(peer.id, true)
        val connector = object : PeerConnector { override fun closeAll() {}; override fun connect(endpoint: Endpoint, pin: PeerPin, identity: TransportIdentity?) = object : PeerSession {
            override val certificate = decodeCertificate(peer.certificate); override fun close() {}
            override fun request(path: String, body: ByteArray): ByteArray { check(path == "/api/v1/health"); val p = repo.doc.peer(peer.id); return Wire.encode(Envelope(true, Health(Hello(), peer.id, p.remotePermissions, p.remoteRevision, p.remoteEpoch, p.remoteOperations), null)) }
        } }
        connections = ConnectionsCoordinator(repo, connector, ConnectionsCoordinatorTest.NetworkPolicy(), ConnectionsCoordinatorTest.Discovery(), foundation.settings, scope)
        connections.foregroundChanged(true); withTimeout(5000) { connections.state.first { it.connectedPeer == peer.id } }
        val noChatNetwork = object : ChatGateway { override suspend fun bundle(ticket: ChatTicket): ChatResult<ChatBundle> = error("No automatic chat"); override suspend fun push(ticket: ChatTicket, delivery: ChatDelivery): ChatResult<ChatReceipt> = error("No automatic chat"); override suspend fun pull(ticket: ChatTicket): ChatResult<ChatPullReply> = error("No automatic chat"); override suspend fun ack(ticket: ChatTicket, delivery: ChatDelivery): ChatResult<Unit> = error("No automatic chat"); override fun cancel() {} }
        chat = ChatCoordinator(repo, olm, noChatNetwork, connections, ChatNotice {}, scope)
        transfers = TransfersCoordinator(repo, transferGateway, downloadChunks, connections, exportFactory, providerIo, scope)
        attachments = AttachmentsCoordinator(repo, age, attachmentChunks, attachmentGateway, connections, chat, importFactory, exportFactory, providerIo, scope)
        offline = OfflineCoordinator(repo, syncGateway, connections, transfers, transferGateway, downloadChunks, scope)
        withTimeout(5000) { transfers.state.first { !it.loading && it.foreground } }
    }
    suspend fun attachmentView() { attachments.visible(true); attachments.select(peer.id); attachmentState { it.access != null && it.peerId == peer.id } }
    suspend fun attachmentState(predicate: (AttachmentsUiState) -> Boolean) = withTimeout(6000) { attachments.state.first(predicate) }
    suspend fun picker(): AttachmentPicker { attachments.prepareImport("private caption", true); return attachmentState { it.picker != null }.picker!!.also { check(attachments.claimPicker(it.ticketId)) } }
    suspend fun saved(): AttachmentRecord { attachmentView(); val p = picker(); attachments.pickerResult(p.ticketId, "content://fixture.documents/document/source", 1); attachmentState { it.rows.singleOrNull()?.available == true && !it.busy && !it.providerBusy }; return repo.doc.attachment(p.id) }
    suspend fun offlineView() { offline.visible(true); withTimeout(6000) { offline.state.first { it.access != null && it.rootId == TransferTestData.root } } }
    suspend fun offlineState(predicate: (OfflineUiState) -> Boolean): OfflineUiState = try { withTimeout(6000) { offline.state.first(predicate) } }
    catch (e: TimeoutCancellationException) { error("Fixture-only timeout: offline=${offline.state.value.error}; metadata=${offline.state.value.metadataError}; pins=${offline.state.value.pins.map { it.candidateState to it.available }}; copies=${repo.doc.downloads.map { it.state to it.remoteFinished }}; transfers=${transfers.state.value.error}") }
    suspend fun close() { releaseOpen.countDown(); releaseClose.countDown(); repo.failWrite = false; if (::connections.isInitialized) connections.foregroundChanged(false); withTimeout(10000) { scope.coroutineContext[Job]?.cancelAndJoin() }; foundation.close() }
}
