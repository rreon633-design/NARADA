package com.narada.connection

import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*

/** Synthetic scoped transport/storage fixture with the REAL host JNI Olm and age adapters. */
class AttachmentFixture(val plain: ByteArray = TransferSamples.bytes(600_001)) {
    val chat = ChatFixture(); val memory = chat.memory; val repository = chat.repository
    val realAge = RustAgeCryptography(); var encryptions = 0
    val age = object : AgeCryptography by realAge { override fun encrypt(name: String, plain: ByteArray): AgeEncrypted { encryptions++; return realAge.encrypt(name, plain) } }
    val chunks = MemoryChunks(); var clock = chat.clock
    var capabilityError: String? = null; var finishError: String? = null
    var afterDownload: () -> Unit = {}; var afterUpload: () -> Unit = {}; var beforeProbe: (AttachmentRecord) -> Unit = {}
    var transformDownload: (ByteArray) -> ByteArray = { it }
    val remote = mutableMapOf<Pair<String, Int>, ByteArray>(); val downloads = mutableListOf<Int>(); val uploads = mutableListOf<Int>()
    var finishes = 0; var begins = 0; var probes = 0; var remoteCipher: ByteArray? = null
    private fun scope() = ChatScope(memory.document.peer(chat.peerId))
    val gateway = object : AttachmentsGateway {
        override suspend fun capabilities(record: AttachmentRecord): AttachmentResult<UploadCapabilities> {
            probes++; beforeProbe(record); capabilityError?.let(::fail)
            return AttachmentResult(UploadCapabilities(1, AttachmentRules.UPLOAD, "age.x25519.v1", TransferRules.CHUNK, AttachmentRules.MAX_PLAIN, AttachmentRules.MAX_CIPHER, 33), scope())
        }
        override suspend fun begin(record: AttachmentRecord): AttachmentResult<UploadStatus> { begins++; assertEquals(record.secret, chat.remoteAttachments[record.messageId]); return status(record) }
        override suspend fun status(record: AttachmentRecord): AttachmentResult<UploadStatus> {
            val d = record.secret!!.descriptor
            val missing = d.hashes.indices.filter { i -> remote[d.blob_id to i]?.let { sha256(it) == d.hashes[i] } != true }
            return AttachmentResult(UploadStatus(1, record.messageId, d.blob_id, d.sha256, "receiving", missing), scope())
        }
        override suspend fun upload(record: AttachmentRecord, index: Int, bytes: ByteArray): AttachmentResult<Unit> {
            val captured = scope(); uploads += index
            assertEquals(record.secret!!.descriptor.hashes[index], sha256(bytes)); remote[record.secret.descriptor.blob_id to index] = bytes.clone()
            afterUpload(); return AttachmentResult(Unit, captured)
        }
        override suspend fun finish(record: AttachmentRecord): AttachmentResult<UploadStatus> {
            finishes++; finishError?.let(::fail); val s = chat.remoteAttachments[record.messageId]!!
            val bytes = s.descriptor.hashes.indices.flatMap { remote[s.descriptor.blob_id to it]!!.asIterable() }.toByteArray()
            val decrypted = realAge.decrypt(s, bytes); assertArrayEquals(plain, decrypted); decrypted.fill(0)
            return AttachmentResult(UploadStatus(1, record.messageId, s.descriptor.blob_id, s.descriptor.sha256, "available", emptyList()), scope())
        }
        override suspend fun download(record: AttachmentRecord, index: Int): AttachmentResult<ByteArray> {
            val captured = scope(); downloads += index; val d = record.secret!!.descriptor; val bytes = remoteCipher!!
            val data = transformDownload(bytes.copyOfRange(index * TransferRules.CHUNK, index * TransferRules.CHUNK + d.length(index)))
            afterDownload(); return AttachmentResult(data, captured)
        }
        override fun cancel() {}
    }
    var engine = AttachmentsEngine(repository, age, chunks, gateway, now = { clock })
    init {
        val p = memory.document.peer(chat.peerId)
        memory.document = memory.document.copy(version = 6).replace(p.copy(remotePermissions = Permissions(chat = true, attachment = true)))
    }
    fun record(id: String) = memory.document.attachment(id)
    suspend fun outgoing(text: String = "", data: ByteArray = plain): AttachmentRecord {
        val r = engine.prepareImport(chat.peerId, text, true)
        engine.admitImport(r.id, "content://fixture.documents/document/source")
        return engine.import(r.id, "inert.bin", ByteArrayInputStream(data))
    }
    suspend fun delivered(): AttachmentRecord { val r = outgoing(); chat.sync(); return record(r.id) }
    suspend fun incoming(secretOverride: ((AttachmentSecret) -> AttachmentSecret)? = null): AttachmentRecord {
        val encrypted = realAge.encrypt("received.bin", plain); remoteCipher = encrypted.ciphertext
        val s = secretOverride?.invoke(encrypted.secret) ?: encrypted.secret
        val e = chat.remoteMessage("", attachment = s); chat.sync()
        return engine.enqueueReceive(e.message_id, true)
    }
    fun reopen() {
        chat.reopen(); engine = AttachmentsEngine(repository, age, chunks, gateway, now = { clock }); runBlocking { engine.recover() }
    }
}
fun descriptorFor(cipher: ByteArray, id: String = UUID.randomUUID().toString()): BlobDescriptor = BlobDescriptor(blob_id = id, size = cipher.size.toLong(), sha256 = sha256(cipher), hashes = (cipher.indices step TransferRules.CHUNK).map { sha256(cipher.copyOfRange(it, minOf(it + TransferRules.CHUNK, cipher.size))) })
