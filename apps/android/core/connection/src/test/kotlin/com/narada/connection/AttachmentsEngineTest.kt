package com.narada.connection

import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*

class AttachmentsEngineTest {
    @Test fun sealedIntentExistsBeforeDesktopCapabilityProbe() = runBlocking<Unit> {
        val f = AttachmentFixture(); f.beforeProbe = { r -> assertEquals(r, f.memory.document.attachment(r.id)); assertEquals(AttachmentState.PICKER_PENDING, r.state); assertNull(r.secret) }
        val r = f.engine.prepareImport(f.chat.peerId, "caption", true); assertEquals(1, r.uploadVersion); assertEquals(0, f.encryptions)
    }
    @Test fun oldDesktopCannotSilentlyEnablePhoneListenerOrReadInput() = runBlocking<Unit> {
        val f = AttachmentFixture(); f.capabilityError = "ATTACHMENT_UPLOAD_UNSUPPORTED"
        expectFailure("ATTACHMENT_UPLOAD_UNSUPPORTED") { runBlocking { f.engine.prepareImport(f.chat.peerId, "", true) } }
        assertNull(f.memory.document.attachments.single().secret); assertEquals(0, f.encryptions); assertTrue(f.memory.document.chat!!.messages.isEmpty())
    }
    @Test fun fullCiphertextAndPrivateReceiptPrecedeChatQueue() = runBlocking<Unit> {
        val f = AttachmentFixture(); f.chat.beforeCommit = { d -> if (d.chat!!.messages.isNotEmpty()) { val r = d.attachments.single(); assertNotNull(r.receipt); assertEquals(r.secret!!.descriptor.hashes.size, f.chunks.data.keys.count { it.first == r.id }) } }
        val r = f.outgoing(); assertTrue(r.available); assertEquals(1, f.encryptions); assertEquals(r.secret, f.memory.document.chat!!.message(r.messageId).attachment)
        assertArrayEquals(f.plain, f.engine.verifiedPlain(r.id)); assertFalse(r.remoteStored)
    }
    @Test fun selectedUriIsDurableBeforeProviderReadAndRemovedAfterImport() = runBlocking<Unit> {
        val f = AttachmentFixture(byteArrayOf(1)); val r = f.engine.prepareImport(f.chat.peerId, "", true)
        f.engine.admitImport(r.id, "content://fixture.documents/document/selected"); assertEquals(AttachmentState.IMPORTING, f.record(r.id).state)
        val stream = object : ByteArrayInputStream(f.plain) { override fun read(b: ByteArray, off: Int, len: Int): Int { assertNotNull(f.record(r.id).sourceUri); return super.read(b, off, len) } }
        f.engine.import(r.id, "a.bin", stream); assertNull(f.record(r.id).sourceUri)
    }
    @Test fun excessiveCaptionIsRejectedBeforeAnyIntentOrProbe() = runBlocking<Unit> { val f = AttachmentFixture(); expectFailure("ATTACHMENT_CAPTION_LIMIT") { runBlocking { f.engine.prepareImport(f.chat.peerId, "x".repeat(1025), true) } }; assertTrue(f.memory.document.attachments.isEmpty()); assertEquals(0, f.probes) }
    @Test fun oversizeProviderCannotStartEncryptionOrQueueMessage() = runBlocking<Unit> {
        val f = AttachmentFixture(); val r = f.engine.prepareImport(f.chat.peerId, "", true); f.engine.admitImport(r.id, "content://fixture.documents/document/source")
        expectFailure("ATTACHMENT_LIMIT") { runBlocking { f.engine.import(r.id, "large.bin", ByteArrayInputStream(ByteArray(AttachmentRules.MAX_PLAIN + 1))) } }
        assertEquals(0, f.encryptions); assertNull(f.record(r.id).secret); assertTrue(f.memory.document.chat!!.messages.isEmpty())
    }
    @Test fun cannotUploadBeforeOlmMessageReceipt() = runBlocking<Unit> { val f = AttachmentFixture(); val r = f.outgoing(); expectFailure("ATTACHMENT_MESSAGE_PENDING") { runBlocking { f.engine.run(r.id, true) } }; assertEquals(0, f.begins); assertTrue(f.record(r.id).available) }
    @Test fun twoWayFakeTransportUsesRealAgeAndRealOlm() = runBlocking<Unit> {
        val f = AttachmentFixture(); val out = f.delivered(); val sent = f.engine.run(out.id, true); assertTrue(sent.remoteStored); assertEquals(1, f.finishes)
        val incoming = f.incoming(); val saved = f.engine.run(incoming.id, true); assertTrue(saved.available); assertArrayEquals(f.plain, f.engine.verifiedPlain(saved.id))
    }
    @Test fun lostUploadReplyReconcilesRemoteBitmapWithoutReencryption() = runBlocking<Unit> {
        val f = AttachmentFixture(); val r = f.delivered(); val identity = r.secret!!.identity; f.afterUpload = { fail("PEER_TIMEOUT") }
        expectFailure("PEER_TIMEOUT") { runBlocking { f.engine.run(r.id, true) } }; assertEquals(listOf(0), f.uploads); assertTrue(f.record(r.id).available)
        f.reopen(); f.afterUpload = {}; val final = f.engine.run(r.id, true)
        assertTrue(final.remoteStored); assertEquals(r.secret, final.secret); assertEquals(identity, final.secret!!.identity); assertEquals(1, f.encryptions); assertEquals(f.uploads.distinct(), f.uploads)
    }
    @Test fun remoteFullVerificationFailureNeverBecomesAvailabilityReceipt() = runBlocking<Unit> {
        val f = AttachmentFixture(); val r = f.delivered(); f.finishError = "ATTACHMENT_DECRYPT_FAILED"
        expectFailure("ATTACHMENT_DECRYPT_FAILED") { runBlocking { f.engine.run(r.id, true) } }; assertFalse(f.record(r.id).remoteStored); assertTrue(f.record(r.id).available)
        assertEquals(r.secret!!.descriptor.hashes.size, f.record(r.id).uploaded.size)
    }
    @Test fun changedAuthorityDiscardsDownloadResultBeforeDurableCheckpoint() = runBlocking<Unit> {
        val f = AttachmentFixture(); val r = f.incoming(); f.afterDownload = { val p = f.memory.document.peer(r.peerId); f.memory.document = f.memory.document.replace(p.copy(localEpoch = p.localEpoch + 1)) }
        expectFailure("CHAT_ACCESS_CHANGED") { runBlocking { f.engine.run(r.id, true) } }; assertTrue(f.record(r.id).chunks.isEmpty()); assertTrue(f.chunks.data.isEmpty()); assertNull(f.record(r.id).receipt)
    }
    @Test fun alteredDownloadNeverPersistsAChunkOrReceipt() = runBlocking<Unit> { val f = AttachmentFixture(); val r = f.incoming(); f.transformDownload = { it.apply { this[0] = (this[0].toInt() xor 1).toByte() } }; expectFailure("HASH_MISMATCH") { runBlocking { f.engine.run(r.id, true) } }; assertTrue(f.chunks.data.isEmpty()); assertFalse(f.record(r.id).available) }
    @Test fun corruptPrivateCiphertextCannotRemainAvailable() = runBlocking<Unit> { val f = AttachmentFixture(); val r = f.outgoing(); f.chunks.data[r.id to 0]!![0] = 0; expectFailure("LOCAL_COPY_CHANGED") { runBlocking { f.engine.verifiedPlain(r.id) } }; assertEquals(AttachmentState.CORRUPT, f.record(r.id).state); assertFalse(f.record(r.id).available) }
    @Test fun missingPreviouslyVerifiedFragmentInvalidatesTheReceiptProjection() = runBlocking<Unit> { val f = AttachmentFixture(); val r = f.delivered(); f.chunks.data.remove(r.id to 0); expectFailure("LOCAL_COPY_MISSING") { runBlocking { f.engine.run(r.id, true) } }; assertFalse(f.record(r.id).available) }
    @Test fun restartNeverReopensPickerOrStartsARequest() = runBlocking<Unit> { val f = AttachmentFixture(); val r = f.engine.prepareImport(f.chat.peerId, "", true); val calls = f.probes; f.reopen(); assertEquals(calls, f.probes); assertEquals(AttachmentState.CANCELLED, f.record(r.id).state); assertTrue(f.uploads.isEmpty()) }
    @Test fun durableCompleteCipherAfterFailedReceiptWriteCanBeReverifiedButNeverReencrypted() = runBlocking<Unit> {
        val f = AttachmentFixture(); f.chat.beforeCommit = { if (it.attachments.any { r -> r.receipt != null }) fail("STORAGE_UNAVAILABLE") }
        expectFailure("STORAGE_UNAVAILABLE") { runBlocking { f.outgoing() } }; val r = f.memory.document.attachments.single(); assertNull(r.receipt); assertEquals(r.secret!!.descriptor.hashes.size, r.chunks.size)
        f.chat.beforeCommit = {}; f.reopen(); expectFailure("ATTACHMENT_MESSAGE_PENDING") { runBlocking { f.engine.run(r.id, true) } }
        assertEquals(1, f.encryptions); assertEquals(r.secret, f.record(r.id).secret); assertTrue(f.record(r.id).available)
    }
    @Test fun incompleteImportRequiresFreshIntentInsteadOfNonceReconstruction() = runBlocking<Unit> {
        val f = AttachmentFixture(); f.chunks.onWrite = { _, i -> if (i == 1) fail("LOCAL_STORAGE_FULL") }
        expectFailure("LOCAL_STORAGE_FULL") { runBlocking { f.outgoing() } }; val r = f.memory.document.attachments.single(); f.chunks.onWrite = { _, _ -> }; f.reopen()
        expectFailure("LOCAL_COPY_MISSING") { runBlocking { f.engine.run(r.id, true) } }; assertEquals(1, f.encryptions); assertNull(f.record(r.id).receipt); assertTrue(f.memory.document.chat!!.messages.isEmpty())
    }
    @Test fun quotaRetainsAllPreviousIntentsWithoutSilentEviction() = runBlocking<Unit> { val f = AttachmentFixture(); repeat(4) { f.engine.prepareImport(f.chat.peerId, "", true) }; val before = f.memory.document.attachments; expectFailure("ATTACHMENT_LOCAL_QUOTA") { runBlocking { f.engine.prepareImport(f.chat.peerId, "", true) } }; assertEquals(before, f.memory.document.attachments) }
    @Test fun invalidAgeIdentityDoesNotCommitInboxRatchetOrAck() = runBlocking<Unit> {
        val f = AttachmentFixture(); val encrypted = f.realAge.encrypt("bad-key.bin", byteArrayOf(1)); val bad = encrypted.secret.copy(identity = "AGE-SECRET-KEY-1" + "Q".repeat(59))
        f.chat.remoteMessage("", attachment = bad); val before = f.memory.document.chat!!
        expectFailure("AGE_IDENTITY_INVALID") { runBlocking { f.chat.sync() } }; assertEquals(before.account, f.memory.document.chat!!.account); assertTrue(f.memory.document.chat!!.messages.isEmpty()); assertEquals(0, f.chat.acknowledgments)
    }
    @Test fun duplicateBlobReferenceUnderNewMessageIdNeverAdvancesInboxTwice() = runBlocking<Unit> {
        val f = AttachmentFixture(); val r = f.incoming(); val before = f.memory.document.chat!!; f.chat.remoteMessage("again", UUID.randomUUID().toString(), r.secret)
        expectFailure("BLOB_ID_CONFLICT") { runBlocking { f.chat.sync() } }; assertEquals(before.account, f.memory.document.chat!!.account); assertEquals(before.messages, f.memory.document.chat!!.messages)
    }
    @Test fun explicitCancellationStopsQueuedChatBeforePrivateRemoval() = runBlocking<Unit> { val f = AttachmentFixture(); val r = f.outgoing(); f.engine.cancel(r.id, true); assertEquals(ChatStatus.CANCELLED_NOT_SENT, f.memory.document.chat!!.message(r.messageId).status); f.engine.remove(r.id, true); assertTrue(f.memory.document.attachments.isEmpty()); assertTrue(f.chunks.data.isEmpty()); assertEquals(1, f.memory.document.chat!!.messages.size) }
    @Test fun revokedPeerCannotEraseAlreadyVerifiedReceivedCopy() = runBlocking<Unit> { val f = AttachmentFixture(); val r = f.incoming(); f.engine.run(r.id, true); val p = f.memory.document.peer(r.peerId); f.memory.document = f.memory.document.replace(p.copy(status = TrustStatus.REVOKED, remotePermissions = Permissions(), remoteOperations = emptyList(), blocked = true)).copy(preferredPeer = null); assertArrayEquals(f.plain, f.engine.verifiedPlain(r.id)); assertTrue(f.record(r.id).available) }
}
