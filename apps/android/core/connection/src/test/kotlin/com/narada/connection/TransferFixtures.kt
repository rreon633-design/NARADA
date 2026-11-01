package com.narada.connection

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

object TransferSamples {
    val id = "77777777-7777-4777-8777-777777777777"
    val source = TransferSource(FileSamples.rootId, FileSamples.objectId, FileSamples.version)
    val operations = listOf("health", "permissions", "files.v1", "transfers.v1")
    fun bytes(size: Int = TransferRules.CHUNK * 2 + 17) = ByteArray(size) { ((it * 31 + 7) % 251).toByte() }
    fun bundle(bytes: ByteArray, name: String = "ordinary.bin", sourceVersion: String = FileSamples.version): ManifestBundle {
        val hashes = (bytes.indices step TransferRules.CHUNK).map { i -> sha256(bytes.copyOfRange(i, minOf(i + TransferRules.CHUNK, bytes.size))) }
        val provisional = TransferManifest(1, "0".repeat(64), sourceVersion, name, bytes.size.toString(), TransferRules.CHUNK, hashes.size, sha256(bytes))
        return ManifestBundle(provisional.copy(manifest_id = provisional.digest(hashes)), hashes)
    }
}
class MemoryChunks : TransferChunks {
    val data = mutableMapOf<Pair<String, Int>, ByteArray>()
    var onWrite: (DownloadRecord, Int) -> Unit = { _, _ -> }
    var onRead: (DownloadRecord, Int) -> Unit = { _, _ -> }
    var full = false; var writes = 0; var removals = 0
    override fun reserve(storeId: String, record: DownloadRecord) { demand(!full, "LOCAL_STORAGE_FULL") }
    override fun read(storeId: String, record: DownloadRecord, index: Int): ByteArray? { onRead(record, index); return data[record.id to index]?.clone() }
    override fun write(storeId: String, record: DownloadRecord, index: Int, clear: ByteArray) { onWrite(record, index); demand(!full, "LOCAL_STORAGE_FULL"); data[record.id to index] = clear.clone(); writes++ }
    override fun remove(storeId: String, record: DownloadRecord) { data.keys.filter { it.first == record.id }.toList().forEach(data::remove); removals++ }
}
/** Synthetic in-memory controller fixture; real desktop/TLS/filesystem cases have their own suite. */
class DownloadFixture(val bytes: ByteArray = TransferSamples.bytes()) {
    val pairing = PeerFixture(); val memory = pairing.repository
    val peer = runBlocking { pairing.paired() }
    var beforeWrite: (TrustDocument) -> Unit = {}
    val repository = object : TrustRepository by memory { override suspend fun mutate(change: (TrustDocument) -> TrustDocument) = memory.mutate { change(it).also(beforeWrite) } }
    val storage = MemoryChunks()
    var prepareCount = 0; var finishCount = 0
    var onPrepare: (DownloadRecord) -> Unit = {}
    var onChunk: (DownloadRecord, Int) -> Unit = { _, _ -> }
    var onFinish: (DownloadRecord) -> Unit = {}
    var transform: (ByteArray) -> ByteArray = { it }
    val requested = mutableListOf<Int>(); val preparedIds = mutableListOf<String>()
    var lastBytes: ByteArray? = null
    val bundle = TransferSamples.bundle(bytes)
    val gateway = object : TransfersGateway {
        override suspend fun prepare(record: DownloadRecord): ManifestBundle { prepareCount++; preparedIds.add(record.id); check(memory.document.download(record.id).state == DownloadState.RUNNING); onPrepare(record); return bundle }
        override suspend fun chunk(record: DownloadRecord, index: Int): ByteArray { requested.add(index); onChunk(record, index)
            return transform(bytes.copyOfRange(index * TransferRules.CHUNK, index * TransferRules.CHUNK + bundle.manifest.length(index))).also { lastBytes = it }
        }
        override suspend fun finish(record: DownloadRecord) { check(memory.document.download(record.id).receipt != null); finishCount++; onFinish(record) }
        override fun cancel() {}
    }
    val engine = DownloadEngine(repository, gateway, storage)
    init { memory.document = memory.document.replace(peer.copy(remotePermissions = FileSamples.grants, remoteOperations = TransferSamples.operations, remoteRevision = 1, remoteEpoch = 1)) }
    suspend fun enqueue(size: Long = bytes.size.toLong(), confirmed: Boolean = true) = engine.enqueue(peer.id, peer.attemptId, TransferSamples.source, "ordinary.bin", size, confirmed)
    fun record(id: String) = memory.document.download(id)
    suspend fun completed(): DownloadRecord = engine.run(enqueue().id)
}
class FakeDocumentProvider : NewDocumentProvider {
    var creates = 0; var writes = 0; var closed = false; var cancelled = false
    val bytes = ByteArrayOutputStream()
    var preflightFailure: String? = null; var createFailure: String? = null; var writeFailure: String? = null
    var readTransform: (ByteArray) -> ByteArray = { it }
    var beforeCreate: () -> Unit = {}; var beforeWrite: () -> Unit = {}; var beforeRead: () -> Unit = {}
    val uri = "content://fixture.documents/tree/root/document/new"
    override fun preflight(tree: String, name: String) { preflightFailure?.let(::fail) }
    override fun create(tree: String, name: String): String { beforeCreate(); creates++; createFailure?.let(::fail); return uri }
    override fun writer(document: String): OutputStream { beforeWrite(); check(document == uri); writes++
        return object : OutputStream() { override fun write(b: Int) { writeFailure?.let(::fail); bytes.write(b) }; override fun write(b: ByteArray, off: Int, len: Int) { writeFailure?.let(::fail); bytes.write(b, off, len) } }
    }
    override fun reader(document: String): InputStream { beforeRead(); return ByteArrayInputStream(readTransform(bytes.toByteArray())) }
    override fun cancel() { cancelled = true }
    override fun close() { closed = true }
}
class TransferPeerFixture(val data: ByteArray = TransferSamples.bytes()) {
    val base = FilePeerFixture(); val repository = base.repository; val memory = base.memory
    val pairing = base.engine; val client = TransfersClient(repository, pairing)
    var health = base.health.copy(operations = TransferSamples.operations)
    val bundle = TransferSamples.bundle(data)
    val record = DownloadRecord(TransferSamples.id, base.peer.id, base.peer.attemptId, TransferSamples.source, "ordinary.bin", data.size.toLong(), System.currentTimeMillis(), state = DownloadState.RUNNING, runId = UUID.randomUUID().toString())
    var count = 0; var healthCount = 0; var chunkCount = 0; var failCode: String? = null
    var afterControl: (String) -> Unit = {}; var afterChunk: () -> Unit = {}; var beforeHealth: (Int) -> Unit = {}
    var transform: (JsonElement) -> JsonElement = { it }; var chunkTransform: (ByteArray) -> ByteArray = { it }
    var returnedBytes: ByteArray? = null
    val session = object : PeerSession {
        override val certificate = base.session.certificate
        override fun close() {}
        override fun request(path: String, body: ByteArray): ByteArray {
            if (path == "/api/v1/health") { healthCount++; beforeHealth(healthCount); return Wire.encode(Envelope(true, health, null)) }
            check(path == TransferRules.PATH); count++
            val action = Wire.decode<TransferWire>(body).action; val kind = action["kind"]!!.jsonPrimitive.content
            val result: JsonElement = when (kind) {
                "prepare" -> Wire.json.encodeToJsonElement(TransferStatusReply(record.id, "ready", bundle.manifest, emptyList(), data.size.toString(), null, null))
                "manifest" -> { val offset = action["offset"]!!.jsonPrimitive.int; val end = minOf(offset + 32, bundle.hashes.size)
                    Wire.json.encodeToJsonElement(TransferManifestPage(record.id, bundle.manifest.manifest_id, offset, bundle.hashes.subList(offset, end), if (end < bundle.hashes.size) end else null)) }
                "finish" -> Wire.json.encodeToJsonElement(TransferFinished(true))
                else -> error("unsupported test action")
            }
            afterControl(kind)
            return if (failCode != null) Wire.encode(Envelope<JsonElement>(false, null, RemoteError(failCode!!, "PRIVATE UNTRUSTED PROSE"))) else Wire.encode(Envelope(true, transform(result), null))
        }
        override fun getChunk(transferId: String, index: Int, expectedLength: Int): ByteArray {
            check(transferId == record.id); chunkCount++
            val bytes = chunkTransform(data.copyOfRange(index * TransferRules.CHUNK, index * TransferRules.CHUNK + expectedLength)); returnedBytes = bytes; afterChunk(); return bytes
        }
    }
    init { memory.document = memory.document.copy(version = 3, downloads = listOf(record)) }
    fun current() = memory.document.peer(record.peerId)
    fun withManifest() = record.copy(manifest = bundle.manifest, hashes = bundle.hashes)
    suspend fun prepare() = client.prepare(current(), session, record)
    suspend fun chunk(index: Int = 0): ByteArray { val r = withManifest(); memory.document = memory.document.replaceDownload(r); return client.chunk(current(), session, r, index) }
}
