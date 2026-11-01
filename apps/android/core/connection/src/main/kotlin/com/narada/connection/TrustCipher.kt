package com.narada.connection

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Standard AES-256-GCM envelope; keys are supplied by AndroidKeyStore in production. */
object TrustCipher {
    private val magic = "NARADA-TRUST-1\u0000".toByteArray(Charsets.US_ASCII)
    private fun aad(id: String): ByteArray { canonicalUuid(id); return "NARADA-ANDROID-TRUST-1|$id".toByteArray(Charsets.US_ASCII) }
    fun seal(id: String, key: SecretKey, plaintext: ByteArray): ByteArray {
        demand(plaintext.size <= Wire.MAX_STORE, "BODY_TOO_LARGE")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(aad(id)) }
        demand(cipher.iv.size == 12, "KEYSTORE_UNAVAILABLE")
        return magic + cipher.iv + cipher.doFinal(plaintext)
    }
    fun unseal(id: String, key: SecretKey, ciphertext: ByteArray): ByteArray {
        demand(ciphertext.size in (magic.size + 28)..(Wire.MAX_STORE + magic.size + 28) && ciphertext.copyOfRange(0, magic.size).contentEquals(magic), "TRUST_STORE_INVALID")
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(magic.size, magic.size + 12)))
            updateAAD(aad(id)); doFinal(ciphertext, magic.size + 12, ciphertext.size - magic.size - 12)
        }
    }
}
