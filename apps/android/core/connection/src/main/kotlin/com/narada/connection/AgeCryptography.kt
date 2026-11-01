package com.narada.connection

import java.util.UUID

class AgeEncrypted(val secret: AttachmentSecret, val ciphertext: ByteArray) {
    override fun toString() = "AgeEncrypted([private key and ciphertext redacted])"
}
interface AgeCryptography {
    fun info()
    fun validateIdentity(secret: AttachmentSecret)
    fun encrypt(name: String, plain: ByteArray): AgeEncrypted
    /** Returns plaintext only AFTER full cipher, age STREAM final authentication and plain hash. */
    fun decrypt(secret: AttachmentSecret, ciphertext: ByteArray): ByteArray
}
object NativeAgeBridge {
    init { System.loadLibrary("narada_age") }
    @JvmStatic external fun info(): String
    @JvmStatic external fun validIdentity(secret: ByteArray): Boolean
    @JvmStatic external fun encrypt(plain: ByteArray): Array<ByteArray>
    @JvmStatic external fun decrypt(ciphertext: ByteArray, secret: ByteArray): ByteArray
}
class RustAgeCryptography : AgeCryptography {
    private inline fun <T> native(block: () -> T): T = try { block() }
        catch (e: ConnectionFailure) { throw e }
        catch (_: LinkageError) { fail("AGE_UNAVAILABLE") }
        catch (e: IllegalStateException) { fail(if (e.message in setOf("AGE_IDENTITY_INVALID", "AGE_DECRYPT_FAILED", "AGE_ENCRYPT_FAILED", "ATTACHMENT_LIMIT")) e.message!! else "AGE_UNAVAILABLE") }
        catch (_: RuntimeException) { fail("AGE_UNAVAILABLE") }
    private val compatible by lazy { native { demand(NativeAgeBridge.info() == "narada-age/1;age/0.12.1;x25519/v1;max-plain/8388608", "AGE_INCOMPATIBLE") } }
    override fun info() { compatible }
    override fun validateIdentity(secret: AttachmentSecret) {
        info(); secret.validate(); val key = secret.identity.toByteArray(Charsets.US_ASCII)
        try { demand(native { NativeAgeBridge.validIdentity(key) }, "AGE_IDENTITY_INVALID") } finally { key.fill(0) }
    }
    override fun encrypt(name: String, plain: ByteArray): AgeEncrypted {
        info(); AttachmentRules.name(name); demand(plain.size <= AttachmentRules.MAX_PLAIN, "ATTACHMENT_LIMIT")
        val result = native { NativeAgeBridge.encrypt(plain) }
        try {
            demand(result.size == 2 && result[0].size in 1..128 && result[1].size in 1..AttachmentRules.MAX_CIPHER, "AGE_UNAVAILABLE")
            val cipher = result[1]
            val hashes = (cipher.indices step TransferRules.CHUNK).map { start ->
                val chunk = cipher.copyOfRange(start, minOf(start + TransferRules.CHUNK, cipher.size))
                try { sha256(chunk) } finally { chunk.fill(0) }
            }
            val secret = AttachmentSecret(BlobDescriptor(blob_id = UUID.randomUUID().toString(), size = cipher.size.toLong(), sha256 = sha256(cipher), hashes = hashes), name, plain.size.toLong(), sha256(plain), result[0].toString(Charsets.US_ASCII))
            validateIdentity(secret); return AgeEncrypted(secret, cipher)
        } catch (e: Exception) { result.forEach { it.fill(0) }; throw e }
        finally { result.firstOrNull()?.fill(0) }
    }
    override fun decrypt(secret: AttachmentSecret, ciphertext: ByteArray): ByteArray {
        validateIdentity(secret)
        demand(ciphertext.size.toLong() == secret.descriptor.size && sha256(ciphertext) == secret.descriptor.sha256, "HASH_MISMATCH")
        val key = secret.identity.toByteArray(Charsets.US_ASCII)
        val plain = try { native { NativeAgeBridge.decrypt(ciphertext, key) } } finally { key.fill(0) }
        try { demand(plain.size.toLong() == secret.plaintext_size && sha256(plain) == secret.plaintext_sha256, "HASH_MISMATCH"); return plain }
        catch (e: Exception) { plain.fill(0); throw e }
    }
}
/** Encoded keys are software secrets protected at rest by the aggregate's AndroidKeyStore seal.
 * Their presence does not mean hardware-held attachment keys, attestation or guaranteed erasure. */
fun verifyProtectedAttachments(doc: TrustDocument, crypto: AgeCryptography = RustAgeCryptography()) {
    val secrets = doc.chat?.messages.orEmpty().mapNotNull { it.attachment } + doc.attachments.mapNotNull { it.secret }
    secrets.distinct().forEach(crypto::validateIdentity)
}
