package com.narada.android.connection

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.narada.android.data.ConnectionAnchor
import com.narada.android.data.MetadataDatabase
import com.narada.connection.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

interface ConnectionKeys {
    fun hasOwnedAliases(): Boolean
    fun create(storeId: String)
    fun identity(storeId: String): TransportIdentity
    fun seal(storeId: String, plaintext: ByteArray): ByteArray
    fun unseal(storeId: String, ciphertext: ByteArray): ByteArray
}
@Singleton
class AndroidConnectionKeys @Inject constructor() : ConnectionKeys {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(id: String, purpose: String): String { canonicalUuid(id); return "narada.connection.$purpose.$id" }
    override fun hasOwnedAliases(): Boolean = store().aliases().toList().any { it.startsWith("narada.connection.") }
    override fun create(storeId: String) {
        val store = store()
        demand(!store.containsAlias(alias(storeId, "ec")) && !store.containsAlias(alias(storeId, "seal")), "IDENTITY_CHANGED")
        val now = System.currentTimeMillis()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(KeyGenParameterSpec.Builder(alias(storeId, "ec"), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .setCertificateSubject(X500Principal("CN=NARADA Android transport identity"))
            .setCertificateSerialNumber(BigInteger(128, SecureRandom()).add(BigInteger.ONE))
            .setCertificateNotBefore(Date(now - 300_000))
            .setCertificateNotAfter(Date(now + 3_650L * 86_400_000))
            .build())
        generator.generateKeyPair()
        // Client-only certificate. AndroidKeyStore provides a self-signed P-256 certificate;
        // no attestation/device properties and no exported private-key serialization.
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias(storeId, "seal"), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
        }.generateKey()
        identity(storeId) // Verify the actual certificate/self-signature and a fresh key-possession challenge.
    }
    override fun identity(storeId: String): TransportIdentity {
        val store = store()
        val entry = store.getEntry(alias(storeId, "ec"), null) as? KeyStore.PrivateKeyEntry ?: fail("IDENTITY_KEY_MISSING")
        demand(entry.privateKey.encoded == null, "KEYSTORE_UNAVAILABLE")
        demand(store.containsAlias(alias(storeId, "seal")), "IDENTITY_KEY_MISSING")
        val certificate = Certificates.parse(entry.certificate.encoded)
        val info = KeyFactory.getInstance("EC", "AndroidKeyStore").getKeySpec(entry.privateKey, KeyInfo::class.java)
        @Suppress("DEPRECATION")
        val label = if (info.isInsideSecureHardware) "AndroidKeyStore · hardware-backed (OS-reported)" else "AndroidKeyStore · software-backed (OS-reported)"
        return TransportIdentity(certificate, entry.privateKey, label).also(Certificates::verifyKey)
    }
    private fun secret(id: String): SecretKey = (store().getKey(alias(id, "seal"), null) as? SecretKey ?: fail("IDENTITY_KEY_MISSING")).also { demand(it.encoded == null, "KEYSTORE_UNAVAILABLE") }
    internal fun transferKey(storeId: String): SecretKey = secret(storeId) // Existing nonexportable key; distinct transfer AAD, no new identity.
    override fun seal(storeId: String, plaintext: ByteArray): ByteArray = TrustCipher.seal(storeId, secret(storeId), plaintext)
    override fun unseal(storeId: String, ciphertext: ByteArray): ByteArray = TrustCipher.unseal(storeId, secret(storeId), ciphertext)

}
interface ProtectedBlob {
    fun hasEvidence(): Boolean
    fun read(): ByteArray
    fun write(bytes: ByteArray)
}
class AtomicProtectedBlob(directory: File) : ProtectedBlob {
    private val directory = directory
    private val atomic = AtomicFile(File(directory, "trust.bin"))
    override fun hasEvidence() = directory.exists() && directory.listFiles()?.isNotEmpty() == true
    override fun read(): ByteArray = atomic.openRead().use { it.readConnectionBounded() }
    override fun write(bytes: ByteArray) {
        demand(bytes.size <= Wire.MAX_STORE + 128, "BODY_TOO_LARGE")
        if (!directory.isDirectory && !directory.mkdirs()) fail("STORAGE_UNAVAILABLE")
        val stream = atomic.startWrite()
        try { stream.write(bytes); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (failure: Exception) { atomic.failWrite(stream); throw failure }
        // AtomicFile logs some rename/sync failures; read-back and directory fsync make them visible.
        demand(read().contentEquals(bytes), "STORAGE_UNAVAILABLE")
        val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}
private fun InputStream.readConnectionBounded(): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val read = read(buffer); if (read < 0) break
        demand(read > 0 && output.size() + read <= Wire.MAX_STORE + 128, "BODY_TOO_LARGE")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
interface EnrollmentAnchor {
    suspend fun read(): ConnectionAnchor?
    suspend fun reserve(id: String)
    suspend fun bind(id: String, deviceId: String, fingerprint: String)
}
class RoomEnrollment(private val database: MetadataDatabase) : EnrollmentAnchor {
    override suspend fun read() = database.dao().connectionAnchor()
    override suspend fun reserve(id: String) = database.dao().reserveConnectionAnchor(ConnectionAnchor(storeId = id, status = "PREPARING", deviceId = null, fingerprint = null))
    override suspend fun bind(id: String, deviceId: String, fingerprint: String) { demand(database.dao().bindConnectionAnchor(id, deviceId, fingerprint) == 1, "IDENTITY_CHANGED") }
}

/** One encrypted, atomic authority for trust and revocation. Transport/AES keys are never serialized. Olm account/session secrets exist only inside this encrypted aggregate. */
class ProtectedConnectionsRepository(private val anchor: EnrollmentAnchor, private val keys: ConnectionKeys, private val blob: ProtectedBlob) : TrustRepository {
    private val mutex = Mutex()
    private val crypto: OlmCryptography = VodozemacOlm() // Loads native code lazily, only for enrolled chat.
    override suspend fun read(): TrustDocument? = guarded { load() }
    override suspend fun initialize(): TrustDocument = guarded {
        load()?.let { return@guarded it }
        val id = UUID.randomUUID().toString()
        anchor.reserve(id) // Persist reservation BEFORE creating any keys or publishing identity.
        keys.create(id)
        val identity = keys.identity(id)
        val doc = TrustDocument(storeId = id, deviceId = identity.deviceId, fingerprint = identity.fingerprint, certificate = Base64.getEncoder().encodeToString(identity.certificate.encoded))
        persist(doc); anchor.bind(id, doc.deviceId, doc.fingerprint); doc
    }
    override suspend fun identity(): TransportIdentity = guarded {
        val doc = load() ?: fail("IDENTITY_NOT_READY")
        keys.identity(doc.storeId).also { demand(it.deviceId == doc.deviceId && it.fingerprint == doc.fingerprint, "IDENTITY_CHANGED") }
    }
    override suspend fun mutate(change: (TrustDocument) -> TrustDocument): TrustDocument = guarded {
        val before = load() ?: fail("IDENTITY_NOT_READY")
        val proposed = change(before.copy(version = 6)).copy(version = 6)
        demand(proposed.storeId == before.storeId && proposed.deviceId == before.deviceId && proposed.fingerprint == before.fingerprint && proposed.certificate == before.certificate, "IDENTITY_CHANGED")
        before.chat?.requireContinuation(proposed.chat)
        requireAttachmentContinuation(before, proposed)
        if (proposed == before) return@guarded before
        demand(before.generation < Long.MAX_VALUE, "TRUST_STORE_INVALID")
        val after = proposed.copy(generation = before.generation + 1); persist(after); after
    }
    private suspend fun load(): TrustDocument? = try { loadProtected() }
    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (failure: ConnectionFailure) {
        // Wire/shape/certificate errors while reading LOCAL sealed data are storage failures,
        // not a retryable remote response. Never leave networking enabled on malformed state.
        if (failure.code in setOf("IDENTITY_KEY_MISSING", "IDENTITY_CHANGED", "IDENTITY_ANCHOR_MISSING", "TRUST_STORE_MISSING", "TRUST_STORE_INVALID", "KEYSTORE_UNAVAILABLE", "STORAGE_UNAVAILABLE")) throw failure
        fail("TRUST_STORE_INVALID")
    }
    private suspend fun loadProtected(): TrustDocument? {
        val marker = anchor.read()
        if (marker == null) { demand(!blob.hasEvidence() && !keys.hasOwnedAliases(), "IDENTITY_ANCHOR_MISSING"); return null }
        canonicalUuid(marker.storeId); demand(marker.status in setOf("PREPARING", "READY"), "TRUST_STORE_INVALID")
        demand(blob.hasEvidence(), "TRUST_STORE_MISSING")
        val identity = keys.identity(marker.storeId)
        val clear = keys.unseal(marker.storeId, blob.read())
        val doc = try { Wire.decode<TrustDocument>(clear, Wire.MAX_STORE) } finally { clear.fill(0) }
        doc.validate(); verifyProtectedChat(doc, crypto); verifyProtectedAttachments(doc)
        demand(doc.storeId == marker.storeId && doc.deviceId == identity.deviceId && doc.fingerprint == identity.fingerprint && doc.certificate == Base64.getEncoder().encodeToString(identity.certificate.encoded), "IDENTITY_CHANGED")
        if (marker.status == "PREPARING") anchor.bind(marker.storeId, doc.deviceId, doc.fingerprint)
        else demand(marker.deviceId == doc.deviceId && marker.fingerprint == doc.fingerprint, "IDENTITY_CHANGED")
        return doc
    }
    private fun persist(doc: TrustDocument) {
        doc.validate(); verifyProtectedChat(doc, crypto); verifyProtectedAttachments(doc); val clear = Wire.encode(doc, Wire.MAX_STORE)
        try { blob.write(keys.seal(doc.storeId, clear)) } finally { clear.fill(0) }
    }
    private suspend fun <T> guarded(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { action() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (failure: ConnectionFailure) { throw failure }
            catch (_: javax.crypto.AEADBadTagException) { fail("TRUST_STORE_INVALID") }
            catch (_: java.security.GeneralSecurityException) { fail("KEYSTORE_UNAVAILABLE") }
            catch (_: Exception) { fail("STORAGE_UNAVAILABLE") }
        }
    }
}
