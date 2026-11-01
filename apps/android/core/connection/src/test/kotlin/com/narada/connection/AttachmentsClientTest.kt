package com.narada.connection

import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

private class AttachmentPeerFixture(incoming: Boolean = false) {
    val f = AttachmentFixture(TransferSamples.bytes(111)); val client = AttachmentsClient(f.repository, f.chat.peer.engine)
    var health = f.chat.peer.health.copy(permissions = Permissions(chat = true, attachment = true), operations = chatOperations)
    var healthCount = 0; var contentCount = 0; var onHealth: (Int) -> Unit = {}; var error: String? = null
    val sent = mutableListOf<JsonObject>(); var transform: (JsonElement) -> JsonElement = { it }; var binary = ByteArray(0)
    val record: AttachmentRecord
    init { record = runBlocking { (if (incoming) f.incoming() else f.delivered()).copy(state = AttachmentState.RUNNING, runId = UUID.randomUUID().toString()) }; f.memory.document = f.memory.document.replaceAttachment(record); binary = if (incoming) f.remoteCipher!!.clone() else f.chunks.data[record.id to 0]!!.clone() }
    private fun reply(data: JsonElement): ByteArray = error?.let { Wire.encode(Envelope<JsonElement>(false, null, RemoteError(it, "PRIVATE provider or server prose"))) } ?: Wire.encode(Envelope(true, transform(data), null))
    val session = object : PeerSession {
        override val certificate = f.chat.peer.host.certificate.encoded
        override fun close() {}
        override fun request(path: String, body: ByteArray): ByteArray {
            if (path == "/api/v1/health") { healthCount++; onHealth(healthCount); return Wire.encode(Envelope(true, health, null)) }
            assertEquals(AttachmentRules.PATH, path); contentCount++; val wire = Wire.decode<UploadWire>(body); sent += wire.action
            val s = record.secret!!; val kind = wire.action["kind"]!!.jsonPrimitive.content
            return reply(if (kind == "capabilities") Wire.json.encodeToJsonElement(UploadCapabilities(1, AttachmentRules.UPLOAD, "age.x25519.v1", TransferRules.CHUNK, AttachmentRules.MAX_PLAIN, AttachmentRules.MAX_CIPHER, 33))
                else Wire.json.encodeToJsonElement(UploadStatus(1, record.messageId, s.descriptor.blob_id, s.descriptor.sha256, if (kind == "finish") "available" else "receiving", if (kind == "finish") emptyList() else listOf(0))))
        }
        override fun putAttachmentChunk(messageId: String, blobId: String, index: Int, bytes: ByteArray): ByteArray { contentCount++; assertArrayEquals(binary, bytes); return reply(Wire.json.encodeToJsonElement(UploadChunkStored(1, messageId, blobId, index, true))) }
        override fun getBlobChunk(blobId: String, index: Int, expectedLength: Int): ByteArray { contentCount++; assertEquals(record.secret!!.descriptor.blob_id, blobId); return binary }
    }
    fun peer() = f.memory.document.peer(record.peerId)
}
class AttachmentsClientTest {
    @Test fun versionedCapabilityProbeHasPreAndPostHealth() = runBlocking<Unit> { val f = AttachmentPeerFixture(); val r = f.client.capabilities(f.peer(), f.session, f.record); r.value.validate(); assertEquals(2, f.healthCount); assertEquals(setOf("kind"), f.sent.single().keys) }
    @Test fun ordinaryUploadControlNeverIncludesIdentityNameOrPlaintextHash() = runBlocking<Unit> { val f = AttachmentPeerFixture(); f.client.control("begin", f.peer(), f.session, f.record); val action = f.sent.single(); assertEquals(setOf("kind", "message_id", "blob_id"), action.keys); assertFalse(action.toString().contains(f.record.secret!!.identity)); assertEquals(2, f.healthCount) }
    @Test fun mismatchedUploadProtocolIsNotSilentlyDowngraded() = runBlocking<Unit> { val f = AttachmentPeerFixture(); f.transform = { JsonObject(it.jsonObject + ("version" to JsonPrimitive(2))) }; expectFailure("ATTACHMENT_UPLOAD_UNSUPPORTED") { runBlocking { f.client.capabilities(f.peer(), f.session, f.record) } } }
    @Test fun uploadChecksCommittedLocalChunkHashBeforeAnyHealthRequest() = runBlocking<Unit> { val f = AttachmentPeerFixture(); expectFailure("HASH_MISMATCH") { runBlocking { f.client.upload(f.peer(), f.session, f.record, 0, f.binary.clone().apply { this[0] = 0 }) } }; assertEquals(0, f.healthCount); assertEquals(0, f.contentCount) }
    @Test fun exactChunkStoredReceiptIsRequired() = runBlocking<Unit> { val f = AttachmentPeerFixture(); f.transform = { JsonObject(it.jsonObject + ("index" to JsonPrimitive(1))) }; expectFailure("INVALID_ATTACHMENT_RESPONSE") { runBlocking { f.client.upload(f.peer(), f.session, f.record, 0, f.binary) } }; assertEquals(1, f.contentCount) }
    @Test fun successfulBinaryUploadIsHealthFencedAndDoesNotCommitByItself() = runBlocking<Unit> { val f = AttachmentPeerFixture(); f.client.upload(f.peer(), f.session, f.record, 0, f.binary); assertEquals(2, f.healthCount); assertTrue(f.f.record(f.record.id).uploaded.isEmpty()); assertFalse(f.f.record(f.record.id).remoteStored) }
    @Test fun grantWithdrawalStopsBeforeAnyCiphertextUpload() = runBlocking<Unit> { val f = AttachmentPeerFixture(); f.health = f.health.copy(permissions = Permissions(chat = true), grant_revision = 2, session_epoch = 2); expectFailure("ATTACHMENTS_UNSUPPORTED") { runBlocking { f.client.upload(f.peer(), f.session, f.record, 0, f.binary) } }; assertEquals(0, f.contentCount) }
    @Test fun foregroundFenceStopsBeforeCapabilityNegotiation() = runBlocking<Unit> { val f = AttachmentPeerFixture(); expectFailure("ATTACHMENT_STOPPED") { runBlocking { f.client.capabilities(f.peer(), f.session, f.record) { fail("ATTACHMENT_STOPPED") } } }; assertEquals(0, f.healthCount) }
    @Test fun validDownloadStillNeedsPostHealthAndFullEngineVerification() = runBlocking<Unit> { val f = AttachmentPeerFixture(true); val expected = f.binary.clone(); val r = f.client.download(f.peer(), f.session, f.record, 0); assertArrayEquals(expected, r.value); assertEquals(2, f.healthCount); assertNull(f.f.record(f.record.id).receipt) }
    @Test fun rejectedPostHealthDownloadZeroesTheDiscardedBuffer() = runBlocking<Unit> { val f = AttachmentPeerFixture(true); f.onHealth = { if (it == 2) f.health = f.health.copy(grant_revision = 2, session_epoch = 2) }; expectFailure("CHAT_ACCESS_CHANGED") { runBlocking { f.client.download(f.peer(), f.session, f.record, 0) } }; assertTrue(f.binary.all { it == 0.toByte() }); assertNull(f.f.record(f.record.id).receipt) }
    @Test fun substitutedUploadStatusCannotMarkAnotherBlobAvailable() = runBlocking<Unit> { val f = AttachmentPeerFixture(); f.transform = { JsonObject(it.jsonObject + ("blob_id" to JsonPrimitive(UUID.randomUUID().toString()))) }; expectFailure("INVALID_ATTACHMENT_RESPONSE") { runBlocking { f.client.control("finish", f.peer(), f.session, f.record) } }; assertFalse(f.f.record(f.record.id).remoteStored) }
    @Test fun desktopVaultFailureCannotLatchThePhoneIdentity() = runBlocking<Unit> { val f = AttachmentPeerFixture(); f.error = "CONTENT_STORE_LOCKED"; expectFailure("ATTACHMENT_REMOTE_STORAGE") { runBlocking { f.client.capabilities(f.peer(), f.session, f.record) } }; assertNotNull(f.f.memory.read()) }
}
