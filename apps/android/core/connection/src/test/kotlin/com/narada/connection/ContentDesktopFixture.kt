package com.narada.connection

import java.io.ByteArrayInputStream
import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlinx.serialization.json.*

/** Current extended Linux agent, real owner IPC/pinned TLS/JNI and encrypted private files.
 * Software phone test keys and JSSE, not AndroidKeyStore/Conscrypt/Windows/device evidence. */
internal class ContentDesktopFixture : AutoCloseable {
    val host = RealDesktop()
    val share = Files.createTempDirectory("narada-p67-source-").toFile()
    val privateDir = Files.createTempDirectory("narada-p67-private-").toFile()
    val bytes = TransferSamples.bytes(600_001)
    var store = MemoryTrust(); val tls = TlsConnector(SocketSource { Socket() })
    var pairing = PairingEngine(store, tls)
    lateinit var rootId: String; lateinit var peerId: String
    val olm = VodozemacOlm(); val age = RustAgeCryptography()
    lateinit var chat: ChatEngine; lateinit var attachments: AttachmentsEngine; lateinit var offline: OfflineEngine; lateinit var downloads: DownloadEngine
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun storage(folder: String): TransferChunks = EncryptedChunkFiles(File(privateDir, folder).toPath(), object : ChunkCryptography {
        override fun seal(context: ChunkContext, clear: ByteArray) = TransferCipher.seal(context, key, clear)
        override fun unseal(context: ChunkContext, sealed: ByteArray) = TransferCipher.unseal(context, key, sealed)
    })
    val attachmentFiles = storage("attachments"); val downloadFiles = storage("downloads")
    private val admission = ContentAdmission()
    var beforeUpload: suspend (Int) -> Unit = {}; var afterUpload: suspend (Int) -> Unit = {}; var afterFinish: suspend () -> Unit = {}; var beforeBlob: suspend (Int) -> Unit = {}
    var afterPage: suspend (SyncTicket, SyncResult) -> Unit = { _, _ -> }
    val uploaded = mutableListOf<Int>(); val fetched = mutableListOf<Int>(); val pages = mutableListOf<SyncTicket>(); val prepared = mutableListOf<DownloadRecord>()
    val chatGateway = PacedChatGateway(object : ChatGateway {
        override suspend fun bundle(ticket: ChatTicket) = pairing.withActiveSession(peerId) { p, s -> ChatClient(store, pairing).bundle(p, s, ticket) }
        override suspend fun push(ticket: ChatTicket, delivery: ChatDelivery) = pairing.withActiveSession(peerId) { p, s -> ChatClient(store, pairing).push(p, s, ticket, delivery) }
        override suspend fun pull(ticket: ChatTicket) = pairing.withActiveSession(peerId) { p, s -> ChatClient(store, pairing).pull(p, s, ticket) }
        override suspend fun ack(ticket: ChatTicket, delivery: ChatDelivery) = pairing.withActiveSession(peerId) { p, s -> ChatClient(store, pairing).ack(p, s, ticket, delivery) }
        override fun cancel() = tls.closeAll()
    }, admission)
    val attachmentGateway = PacedAttachmentsGateway(object : AttachmentsGateway {
        override suspend fun capabilities(record: AttachmentRecord) = pairing.withActiveSession(peerId) { p, s -> AttachmentsClient(store, pairing).capabilities(p, s, record) }
        override suspend fun begin(record: AttachmentRecord) = pairing.withActiveSession(peerId) { p, s -> AttachmentsClient(store, pairing).control("begin", p, s, record) }
        override suspend fun status(record: AttachmentRecord) = pairing.withActiveSession(peerId) { p, s -> AttachmentsClient(store, pairing).control("status", p, s, record) }
        override suspend fun finish(record: AttachmentRecord) = pairing.withActiveSession(peerId) { p, s -> AttachmentsClient(store, pairing).control("finish", p, s, record) }.also { afterFinish() }
        override suspend fun upload(record: AttachmentRecord, index: Int, bytes: ByteArray): AttachmentResult<Unit> { beforeUpload(index); uploaded += index; return pairing.withActiveSession(peerId) { p, s -> AttachmentsClient(store, pairing).upload(p, s, record, index, bytes) }.also { afterUpload(index) } }
        override suspend fun download(record: AttachmentRecord, index: Int): AttachmentResult<ByteArray> { beforeBlob(index); fetched += index; return pairing.withActiveSession(peerId) { p, s -> AttachmentsClient(store, pairing).download(p, s, record, index) } }
        override fun cancel() = tls.closeAll()
    }, admission)
    val syncGateway = PacedSyncGateway(object : SyncGateway {
        override suspend fun page(ticket: SyncTicket): SyncResult { pages += ticket; return pairing.withActiveSession(peerId) { p, s -> SyncClient(store, pairing).page(p, s, ticket) }.also { afterPage(ticket, it) } }
        override fun cancel() = tls.closeAll()
    }, admission)
    val transferGateway = PacedTransfersGateway(object : TransfersGateway {
        override suspend fun prepare(record: DownloadRecord): ManifestBundle { prepared += record; return pairing.withActiveSession(peerId) { p, s -> TransfersClient(store, pairing).prepare(p, s, record) } }
        override suspend fun chunk(record: DownloadRecord, index: Int) = pairing.withActiveSession(peerId) { p, s -> TransfersClient(store, pairing).chunk(p, s, record, index) }
        override suspend fun finish(record: DownloadRecord) { pairing.withActiveSession(peerId) { p, s -> TransfersClient(store, pairing).finish(p, s, record) } }
        override fun cancel() = tls.closeAll()
    }, admission)
    suspend fun setup(many: Boolean = false) {
        File(share, "source.bin").writeBytes(bytes); File(share, "empty.bin").writeBytes(byteArrayOf())
        if (many) repeat(20) { File(share, "entry-$it.txt").writeText("Disposable source $it") }
        rootId = host.call("register_root", buildJsonObject { put("path", share.path); put("label", "Phase 6–7 disposable root"); put("exclusions", buildJsonArray {}) }).jsonObject["root_id"]!!.jsonPrimitive.content
        host.enable(); val code = PairCode.parse(host.call("start_pairing").jsonObject["code"]!!.jsonPrimitive.content)
        val pending = pairing.begin(code, "Android Phase 6–7 JSSE fixture"); pairing.prove(pending.id)
        host.call("approve_pairing", buildJsonObject { put("attempt_id", pending.attemptId); put("permissions", Wire.json.encodeToJsonElement(grants())) })
        pairing.prove(pending.id); peerId = pairing.finish(pending.id, true).id
        pairing.withActiveSession(peerId) { p, s -> pairing.health(p, s) }; open(); chat.enablePeer(peerId, true)
    }
    fun grants(attachment: Boolean = true, sync: Boolean = true) = Permissions(chat = true, attachment = attachment, roots = listOf(RootGrant(rootId, listOf("browse", "read") + if (sync) listOf("sync_read") else emptyList())))
    fun permissions(permissions: Permissions) { val revision = host.snapshot()["peers"]!!.jsonArray.single().jsonObject["grant_revision"]!!.jsonPrimitive.int; host.call("update_peer_permissions", buildJsonObject { put("peer_id", store.key.deviceId); put("expected_revision", revision); put("permissions", Wire.json.encodeToJsonElement(permissions)) }) }
    private fun open() { chat = ChatEngine(store, olm, age); attachments = AttachmentsEngine(store, age, attachmentFiles, attachmentGateway); offline = OfflineEngine(store, syncGateway); downloads = DownloadEngine(store, transferGateway, downloadFiles) }
    suspend fun restartBoth() { host.restart(); pairing.setEndpoint(peerId, host.snapshot()["endpoint"]!!.jsonPrimitive.content); store = MemoryTrust(store.key, Wire.decode(Wire.encode(store.document, Wire.MAX_STORE), Wire.MAX_STORE)); pairing = PairingEngine(store, tls); open(); chat.recover(); attachments.recover(); downloads.recover() }
    suspend fun sync() = chat.sync(peerId, chatGateway)
    suspend fun outgoing(data: ByteArray = bytes, caption: String = "Phone attachment", name: String = "phone.bin"): AttachmentRecord { val r = attachments.prepareImport(peerId, caption, true); attachments.admitImport(r.id, "content://fixture.documents/document/source"); return attachments.import(r.id, name, ByteArrayInputStream(data)) }
    suspend fun selected(name: String): RemoteEntry = pairing.withActiveSession(peerId) { p, s -> (FilesClient(store, pairing).query(p, s, FileQuery.Search(rootId, name)) as FileResult.Page).value.entries.single { it.name == name } }
    suspend fun incoming(name: String = "source.bin"): AttachmentRecord {
        sync(); val entry = selected(name); val id = UUID.randomUUID().toString()
        host.call("send_chat", buildJsonObject { put("message_id", id); put("peer_id", store.key.deviceId); put("text", "Desktop attachment"); put("attachment", Wire.json.encodeToJsonElement(TransferSource(rootId, entry.object_id, entry.version))) })
        sync(); return attachments.enqueueReceive(id, true)
    }
    fun history() = host.call("chat_history", buildJsonObject { put("peer_id", store.key.deviceId); put("before", JsonNull) }).jsonObject["messages"]!!.jsonArray
    fun export(record: AttachmentRecord, name: String = "exported.bin"): ByteArray { host.call("export_attachment", buildJsonObject { put("message_id", record.messageId); put("operation_id", UUID.randomUUID().toString()); put("destination", buildJsonObject { put("root_id", rootId); put("directory_id", JsonNull); put("name", name) }) }); return File(share, name).readBytes() }
    fun reindex() { host.call("reindex_root", buildJsonObject { put("root_id", rootId) }) }
    suspend fun cache() = offline.refresh(peerId, rootId, true)
    suspend fun saved(): OfflinePin { val c = cache(); val p = offline.pin(peerId, rootId, c.entries.single { it.name == "source.bin" }.object_id, true); downloads.run(p.candidate!!); offline.adopt(p.id, downloads, true); return store.document.pin(p.id) }
    override fun close() { tls.closeAll(); host.close(); share.deleteRecursively(); privateDir.deleteRecursively() }
}
