package com.narada.android

import com.narada.android.connection.*
import com.narada.connection.*
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual host JNI and AES-GCM with explicit software test keys, NOT AndroidKeyStore qualification. */
class ProtectedContentJournalTest {
    private class Fixture {
        val anchor = TestAnchor(); val keys = TestKeys(); val blob = TestBlob(); val age = RustAgeCryptography(); val chunks = TestTransferChunks()
        val peer = TransferTestData.peer().copy(remotePermissions = Permissions(chat = true, attachment = true, roots = listOf(RootGrant(TransferTestData.root, listOf("browse", "read", "sync_read")))), remoteOperations = listOf("health", "permissions", "files.v1", "transfers.v1", "chat.olm.v1", "attachments.age.v1", "sync.v1"))
        val plain = "PRIVATE_PLAINTEXT_SENTINEL".toByteArray(); var probes = 0
        fun open() = ProtectedConnectionsRepository(anchor, keys, blob)
        val gateway = object : AttachmentsGateway {
            override suspend fun capabilities(record: AttachmentRecord): AttachmentResult<UploadCapabilities> { probes++; return AttachmentResult(UploadCapabilities(1, AttachmentRules.UPLOAD, "age.x25519.v1", TransferRules.CHUNK, AttachmentRules.MAX_PLAIN, AttachmentRules.MAX_CIPHER, 33), ChatScope(open().read()!!.peer(peer.id))) }
            override suspend fun begin(record: AttachmentRecord): AttachmentResult<UploadStatus> = error("No network in storage test")
            override suspend fun status(record: AttachmentRecord): AttachmentResult<UploadStatus> = error("No network in storage test")
            override suspend fun finish(record: AttachmentRecord): AttachmentResult<UploadStatus> = error("No network in storage test")
            override suspend fun upload(record: AttachmentRecord, index: Int, bytes: ByteArray): AttachmentResult<Unit> = error("No network in storage test")
            override suspend fun download(record: AttachmentRecord, index: Int): AttachmentResult<ByteArray> = error("No network in storage test")
            override fun cancel() {}
        }
        fun engine() = AttachmentsEngine(open(), age, chunks, gateway)
        suspend fun setup() { val r = open(); r.initialize(); r.mutate { it.copy(enabled = true, peers = listOf(peer), preferredPeer = peer.id) }; ChatEngine(r, VodozemacOlm()).enablePeer(peer.id, true) }
        suspend fun saved(): AttachmentRecord { setup(); val e = engine(); val r = e.prepareImport(peer.id, "PRIVATE_CAPTION_SENTINEL", true); e.admitImport(r.id, "content://fixture.documents/document/private"); return e.import(r.id, "PRIVATE_NAME_SENTINEL.bin", ByteArrayInputStream(plain)) }
    }
    private fun failure(code: String? = null, body: suspend () -> Unit) = runBlocking { try { body(); org.junit.Assert.fail("Expected failure") } catch (e: ConnectionFailure) { if (code != null) assertEquals(code, e.code) } }
    @Test fun freshKeyManifestAndOutboxCommitInsideOneSealedAggregate() = runBlocking<Unit> { val f = Fixture(); val a = f.saved(); val doc = f.open().read()!!; assertEquals(6, doc.version); assertEquals(a.secret, doc.chat!!.message(a.messageId).attachment); assertNotNull(a.receipt); assertEquals(ChatStatus.QUEUED, doc.chat!!.message(a.messageId).status); val raw = f.blob.bytes!!.toString(Charsets.ISO_8859_1); listOf(a.secret!!.identity, a.secret!!.name, a.secret!!.plaintext_sha256, "PRIVATE_CAPTION_SENTINEL", "PRIVATE_PLAINTEXT_SENTINEL").forEach { assertFalse(raw.contains(it)) }; assertEquals(1, f.keys.creations) }
    @Test fun reopenedAttachmentReverifiesCiphertextAndNeverAutoUploads() = runBlocking<Unit> { val f = Fixture(); val a = f.saved(); f.engine().recover(); assertArrayEquals(f.plain, f.engine().verifiedPlain(a.id)); assertEquals(a, f.open().read()!!.attachment(a.id)); assertEquals(1, f.probes); assertFalse(a.remoteStored) }
    @Test fun protectedMutationCannotReplaceAnEstablishedAttachmentIdentity() = runBlocking<Unit> { val f = Fixture(); val a = f.saved(); val saved = f.blob.bytes!!.clone(); val other = f.age.encrypt("other.bin", byteArrayOf(2)).secret; failure("ATTACHMENT_STATE_INVALID") { f.open().mutate { it.replaceAttachment(a.copy(secret = other)) } }; assertArrayEquals(saved, f.blob.bytes) }
    @Test fun malformedCanonicalKeyInValidlySealedStateFailsClosedWithoutRekey() = runBlocking<Unit> { val f = Fixture(); val a = f.saved(); val d = f.open().read()!!; val secret = a.secret!!.copy(identity = "AGE-SECRET-KEY-1" + "Q".repeat(59)); val bad = d.copy(attachments = listOf(a.copy(secret = secret)), chat = d.chat!!.replace(d.chat!!.message(a.messageId).copy(attachment = secret, requestHash = chatRequestHash(d.deviceId, a.peerId, a.pairingAttempt, a.messageId, a.text, secret)))); f.blob.write(f.keys.seal(d.storeId, Wire.encode(bad, Wire.MAX_STORE))); val saved = f.blob.bytes!!.clone(); failure("TRUST_STORE_INVALID") { f.open().read() }; failure("TRUST_STORE_INVALID") { f.open().initialize() }; assertEquals(1, f.keys.creations); assertArrayEquals(saved, f.blob.bytes) }
    @Test fun failedIntentPersistenceNeverCallsCapabilityGateway() = runBlocking<Unit> { val f = Fixture(); f.setup(); f.blob.failWrite = true; failure("STORAGE_UNAVAILABLE") { f.engine().prepareImport(f.peer.id, "", true) }; assertEquals(0, f.probes); f.blob.failWrite = false; assertTrue(f.open().read()!!.attachments.isEmpty()) }
    @Test fun removedOrDamagedSealedStateCannotFallBackToUnprotectedAttachmentKeys() = runBlocking<Unit> { val f = Fixture(); f.saved(); val before = f.blob.bytes!!.clone(); f.blob.bytes = before.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }; failure("TRUST_STORE_INVALID") { f.engine().recover() }; assertEquals(1, f.keys.creations); f.blob.bytes = null; failure("TRUST_STORE_MISSING") { f.open().initialize() }; assertEquals(1, f.keys.creations) }
    @Test fun syncCursorsPathsPinsAndPartialMetadataAreSealedNotOrdinaryMetadata() = runBlocking<Unit> {
        val f = Fixture(); f.setup(); val d = f.open().read()!!; val cursor = UUID.randomUUID().toString(); val entry = SyncEntry(TransferTestData.objectId, null, "PRIVATE_PATH_SENTINEL.bin", "PRIVATE_PATH_SENTINEL.bin", "file", "1", "a".repeat(64)); val scope = SyncScope.from(f.peer)
        val work = SyncWork(UUID.randomUUID().toString(), scope, "snapshot", cursor, 2, listOf(entry), System.currentTimeMillis())
        f.open().mutate { it.replaceSync(SyncCache(f.peer.id, TransferTestData.root, work = work)) }; val reopened = f.open().read()!!; assertEquals(work, reopened.syncCaches.single().work); assertTrue(reopened.syncCaches.single().entries.isEmpty()); val raw = f.blob.bytes!!.toString(Charsets.ISO_8859_1); assertFalse(raw.contains(cursor)); assertFalse(raw.contains(entry.name)); assertFalse(raw.contains("sync_read")); assertEquals(d.storeId, reopened.storeId)
    }
    @Test fun pinReferencesAreValidatedOnEveryProtectedRead() = runBlocking<Unit> { val f = Fixture(); f.setup(); val d = f.open().read()!!; val pin = OfflinePin(UUID.randomUUID().toString(), f.peer.id, f.peer.attemptId, TransferTestData.root, TransferTestData.objectId, 1, null, 1, tombstone = true, current = UUID.randomUUID().toString()); val bad = d.copy(pins = listOf(pin)); f.blob.write(f.keys.seal(d.storeId, Wire.encode(bad, Wire.MAX_STORE))); val saved = f.blob.bytes!!.clone(); failure("TRUST_STORE_INVALID") { f.open().read() }; assertArrayEquals(saved, f.blob.bytes); assertEquals(1, f.keys.creations) }
    @Test fun versionFourChatMigratesWithoutResettingAccountIdentityOrDraft() = runBlocking<Unit> { val f = Fixture(); f.setup(); val c = ChatEngine(f.open(), VodozemacOlm()); c.saveDraft(f.peer.id, "retained draft"); val d = f.open().read()!!; f.blob.write(f.keys.seal(d.storeId, Wire.encode(d.copy(version = 4), Wire.MAX_STORE))); assertEquals(4, f.open().read()!!.version); val next = f.open().mutate { it }; assertEquals(6, next.version); assertEquals(d.chat, next.chat); assertEquals(d.deviceId, next.deviceId); assertEquals(1, f.keys.creations) }
}
