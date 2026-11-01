package com.narada.connection

import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class AgeCryptographyTest {
    private val age = RustAgeCryptography()
    @Test fun actualBundledHostBridgeReportsPinnedAgeX25519Mode() { age.info(); assertEquals("narada-age/1;age/0.12.1;x25519/v1;max-plain/8388608", NativeAgeBridge.info()) }
    @Test fun emptyFileHasAuthenticatedNonemptyCiphertext() { val e = age.encrypt("empty.bin", byteArrayOf()); assertTrue(e.ciphertext.isNotEmpty()); assertArrayEquals(byteArrayOf(), age.decrypt(e.secret, e.ciphertext)) }
    @Test fun unicodeNameAndBinaryDataRoundTrip() { val p = TransferSamples.bytes(200_001); val e = age.encrypt("తెలుగు café.bin", p); assertArrayEquals(p, age.decrypt(e.secret, e.ciphertext)); assertEquals("తెలుగు café.bin", e.secret.name) }
    @Test fun freshIdentityAndImmutableCiphertextDifferForSamePlaintext() { val a = age.encrypt("a.bin", byteArrayOf(1)); val b = age.encrypt("a.bin", byteArrayOf(1)); assertNotEquals(a.secret.identity, b.secret.identity); assertNotEquals(a.secret.descriptor.blob_id, b.secret.descriptor.blob_id); assertFalse(a.ciphertext.contentEquals(b.ciphertext)) }
    @Test fun fullPlaintextLimitWorksWithAtMostThirtyThreeChunks() { val p = ByteArray(AttachmentRules.MAX_PLAIN) { 7 }; val e = age.encrypt("max.bin", p); assertTrue(e.secret.descriptor.hashes.size <= 33); assertArrayEquals(p, age.decrypt(e.secret, e.ciphertext)) }
    @Test fun overLimitIsRejectedBeforeNativeEncryption() { expectFailure("ATTACHMENT_LIMIT") { age.encrypt("large.bin", ByteArray(AttachmentRules.MAX_PLAIN + 1)) } }
    @Test fun wrongIdentityNeverReleasesPlaintext() { val a = age.encrypt("a.bin", byteArrayOf(1)); val b = age.encrypt("b.bin", byteArrayOf(2)); expectFailure("AGE_DECRYPT_FAILED") { age.decrypt(a.secret.copy(identity = b.secret.identity), a.ciphertext) } }
    @Test fun completeCipherHashIsCheckedBeforeDecryption() { val e = age.encrypt("a.bin", byteArrayOf(1)); e.ciphertext[e.ciphertext.lastIndex] = (e.ciphertext.last().toInt() xor 1).toByte(); expectFailure("HASH_MISMATCH") { age.decrypt(e.secret, e.ciphertext) } }
    @Test fun forgedCipherHashCannotHideFinalRecordTamper() { val e = age.encrypt("a.bin", TransferSamples.bytes(100001)); e.ciphertext[e.ciphertext.lastIndex] = (e.ciphertext.last().toInt() xor 1).toByte(); expectFailure("AGE_DECRYPT_FAILED") { age.decrypt(e.secret.copy(descriptor = descriptorFor(e.ciphertext)), e.ciphertext) } }
    @Test fun truncationWithMatchingOuterManifestStillFailsAgeAuthentication() { val e = age.encrypt("a.bin", ByteArray(65536)); val c = e.ciphertext.copyOf(e.ciphertext.size - 1); expectFailure("AGE_DECRYPT_FAILED") { age.decrypt(e.secret.copy(descriptor = descriptorFor(c)), c) } }
    @Test fun reorderedActualStreamRecordsFailEvenWithRecomputedManifest() {
        val e = age.encrypt("a.bin", TransferSamples.bytes(200001)); val c = e.ciphertext.clone(); val text = c.toString(Charsets.ISO_8859_1)
        val start = text.indexOf('\n', text.indexOf("\n--- ") + 1) + 1 + 16; assertTrue(start in 100..512)
        repeat(65552) { i -> val b = c[start + i]; c[start + i] = c[start + 65552 + i]; c[start + 65552 + i] = b }
        expectFailure("AGE_DECRYPT_FAILED") { age.decrypt(e.secret.copy(descriptor = descriptorFor(c)), c) }
    }
    @Test fun plainSizeAndHashAreIndependentlyRequired() { val e = age.encrypt("a.bin", byteArrayOf(1, 2)); expectFailure("HASH_MISMATCH") { age.decrypt(e.secret.copy(plaintext_size = 1), e.ciphertext) }; expectFailure("HASH_MISMATCH") { age.decrypt(e.secret.copy(plaintext_sha256 = "0".repeat(64)), e.ciphertext) } }
    @Test fun invalidCanonicalKeyAndUnsafeNameAreRefused() { val e = age.encrypt("a.bin", byteArrayOf()); expectFailure("AGE_IDENTITY_INVALID") { age.validateIdentity(e.secret.copy(identity = "AGE-SECRET-KEY-1" + "Q".repeat(59))) }; listOf("a/b", "a\\b", "\u0000", "é".repeat(91)).forEach { expectFailure { age.encrypt(it, byteArrayOf()) } } }
    @Test fun keyAndPlainManifestNeverEnterTheOrdinaryChunkAdapter() {
        val e = age.encrypt("PRIVATE_FILENAME_SENTINEL.bin", byteArrayOf(1)); val r = AttachmentRecord(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "a".repeat(64), UUID.randomUUID().toString(), ChatDirection.INCOMING, 1, state = AttachmentState.PAUSED, secret = e.secret)
        val encoded = Wire.encode(r.ciphertextRecord()).decodeToString(); assertFalse(encoded.contains(e.secret.identity)); assertFalse(encoded.contains(e.secret.plaintext_sha256)); assertFalse(encoded.contains(e.secret.name)); assertTrue(encoded.contains(e.secret.descriptor.sha256)); assertFalse(r.toString().contains(e.secret.name)); assertFalse(e.toString().contains(e.secret.identity))
    }
    @Test fun wireRejectsUnknownAndDuplicateSecretFields() { val e = age.encrypt("a.bin", byteArrayOf(1)); val s = Wire.encode(e.secret).decodeToString(); listOf(s.replaceFirst("{", "{\"unexpected\":1,"), s.replaceFirst("{", "{\"identity\":\"duplicate\","), s.replace("\"plaintext_size\":1", "\"plaintext_size\":\"1\"")).forEach { expectFailure { Wire.decode<AttachmentSecret>(it.toByteArray()) } } }
}
