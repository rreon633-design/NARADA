package com.narada.connection

import java.util.UUID
import kotlinx.coroutines.runBlocking

class OfflineFixture {
    val peerFixture = PeerFixture(); val memory = peerFixture.repository
    val peer = runBlocking { peerFixture.paired() }; val root = FileSamples.rootId
    var beforeWrite: (TrustDocument) -> Unit = {}
    val repository = object : TrustRepository by memory { override suspend fun mutate(change: (TrustDocument) -> TrustDocument) = memory.mutate { change(it).also(beforeWrite) } }
    var clock = 1_793_404_800_000L
    val bytes = TransferSamples.bytes(8193)
    val entry = SyncEntry(FileSamples.objectId, null, "pinned.bin", "pinned.bin", "file", bytes.size.toString(), FileSamples.version)
    var entries = listOf(entry); val requests = mutableListOf<SyncTicket>(); var beforePage: (SyncTicket) -> Unit = {}
    var page: (SyncTicket) -> SyncPage = { t -> if (t.work.kind == "snapshot") SyncPage(root, "snapshot", entries, emptyList(), null, UUID.randomUUID().toString(), 1)
        else SyncPage(root, "changes", emptyList(), emptyList(), null, UUID.randomUUID().toString(), t.work.fence!!) }
    val gateway = object : SyncGateway {
        override suspend fun page(ticket: SyncTicket): SyncResult { requests += ticket; val captured = ticket.work.scope; beforePage(ticket); return SyncResult(this@OfflineFixture.page(ticket), captured) }
        override fun cancel() {}
    }
    var engine = OfflineEngine(repository, gateway, now = { clock })
    val chunks = MemoryChunks(); var beforeChunk: (DownloadRecord, Int) -> Unit = { _, _ -> }
    val payloads = mutableMapOf(FileSamples.version to bytes)
    val prepared = mutableListOf<DownloadRecord>()
    val transfers = object : TransfersGateway {
        override suspend fun prepare(record: DownloadRecord): ManifestBundle { prepared += record; return TransferSamples.bundle(payloads[record.source.expected_version]!!, record.name, record.source.expected_version) }
        override suspend fun chunk(record: DownloadRecord, index: Int): ByteArray { beforeChunk(record, index); val bytes = payloads[record.source.expected_version]!!; return bytes.copyOfRange(index * TransferRules.CHUNK, minOf(bytes.size, (index + 1) * TransferRules.CHUNK)) }
        override suspend fun finish(record: DownloadRecord) {}
        override fun cancel() {}
    }
    val downloads = DownloadEngine(repository, transfers, chunks, now = { clock })
    init { memory.document = memory.document.copy(version = 6).replace(peer.copy(remotePermissions = Permissions(roots = listOf(RootGrant(root, listOf("browse", "read", "sync_read")))), remoteRevision = 1, remoteEpoch = 1, remoteOperations = chatOperations)) }
    suspend fun snapshot(): SyncCache = engine.refresh(peer.id, root, true)
    fun cache() = memory.document.syncCache(peer.id, root)
    suspend fun pin(): OfflinePin { snapshot(); return engine.pin(peer.id, root, entry.object_id, true) }
    suspend fun saved(): OfflinePin { val p = pin(); downloads.run(p.candidate!!); engine.adopt(p.id, downloads, true); return memory.document.pin(p.id) }
    fun upsert(version: String = "b".repeat(64)) {
        val next = entry.copy(version = version); payloads[version] = TransferSamples.bytes(bytes.size).apply { this[0] = (this[0].toInt() xor 1).toByte() }
        entries = listOf(next); page = { t -> if (t.work.kind == "snapshot") SyncPage(root, "snapshot", entries, emptyList(), null, UUID.randomUUID().toString(), 2)
            else SyncPage(root, "changes", emptyList(), listOf(SyncEvent(t.work.fence!! + 1, "upsert", next.object_id, next)), null, UUID.randomUUID().toString(), t.work.fence!! + 1) }
    }
    fun deleted(replacement: SyncEntry? = null) { entries = listOfNotNull(replacement); page = { t ->
        if (t.work.kind == "snapshot") SyncPage(root, "snapshot", entries, emptyList(), null, UUID.randomUUID().toString(), 3)
        else { val start = t.work.fence!!; val events = listOf(SyncEvent(start + 1, "deleted", entry.object_id, null)) + listOfNotNull(replacement?.let { SyncEvent(start + 2, "upsert", it.object_id, it) }); SyncPage(root, "changes", emptyList(), events, null, UUID.randomUUID().toString(), events.last().sequence) }
    } }
    fun reopen() { memory.document = Wire.decode(Wire.encode(memory.document, Wire.MAX_STORE), Wire.MAX_STORE); engine = OfflineEngine(repository, gateway, now = { clock }) }
}
