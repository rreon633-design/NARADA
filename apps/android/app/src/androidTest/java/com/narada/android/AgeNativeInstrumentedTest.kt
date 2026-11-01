package com.narada.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.narada.connection.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** COMPILED ONLY until a recorded device/ABI/API run exists. Not Android/native execution proof. */
@RunWith(AndroidJUnit4::class)
class AgeNativeInstrumentedTest {
    @Test fun bundledAgeModeAndFreshX25519IdentitiesMatchPinnedContract() { assertEquals("narada-age/1;age/0.12.1;x25519/v1;max-plain/8388608", NativeAgeBridge.info()); val c = RustAgeCryptography(); val a = c.encrypt("one.bin", byteArrayOf(1)); val b = c.encrypt("one.bin", byteArrayOf(1)); assertNotEquals(a.secret.identity, b.secret.identity); assertFalse(a.ciphertext.contentEquals(b.ciphertext)); c.validateIdentity(a.secret); c.validateIdentity(b.secret) }
    @Test fun multiRecordAgeStreamAndPlaintextHashRoundTripOnThisAbi() { val c = RustAgeCryptography(); val plain = ByteArray(140_001) { (it % 251).toByte() }; val e = c.encrypt("నమూనా.bin", plain); assertArrayEquals(plain, c.decrypt(e.secret, e.ciphertext)); val changed = e.ciphertext.clone().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }; try { c.decrypt(e.secret, changed); org.junit.Assert.fail("Tampered ciphertext accepted") } catch (e: ConnectionFailure) { assertEquals("HASH_MISMATCH", e.code) } }
    @Test fun typedOlmBodyCarriesAgeSecretAndStillRequiresIndependentAgeAuthentication() { val age = RustAgeCryptography(); val e = age.encrypt("inert.bin", byteArrayOf(3, 2, 1)); val olm = VodozemacOlm(); val a = olm.bundle(olm.create().account, "a".repeat(64), "b".repeat(64)); val b = olm.bundle(olm.create().account, "b".repeat(64), "a".repeat(64)); val body = ChatBody(message_id = UUID.randomUUID().toString(), sender = a.bundle.transport_id, recipient = b.bundle.transport_id, text = "", attachment = e.secret); val encrypted = olm.encrypt(a.account, a.bundle, b.bundle, null, body); val decrypted = olm.decrypt(b.account, b.bundle, a.bundle, emptyList(), encrypted.envelope); assertEquals(e.secret, decrypted.attachment); assertArrayEquals(byteArrayOf(3, 2, 1), age.decrypt(decrypted.attachment!!, e.ciphertext)) }
}
