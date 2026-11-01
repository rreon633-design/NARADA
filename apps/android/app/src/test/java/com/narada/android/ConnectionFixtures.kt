package com.narada.android

import com.narada.connection.*

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.UUID
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object AndroidTestIdentities {
    fun create(server: Boolean = false, curve: String = "secp256r1", expires: Long = System.currentTimeMillis() + 86_400_000): TransportIdentity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
        val subject = X500Name("CN=NARADA disposable JVM test identity")
        val builder = JcaX509v3CertificateBuilder(subject, BigInteger(120, SecureRandom()).add(BigInteger.ONE), Date(System.currentTimeMillis() - 300_000), Date(expires), subject, pair.public)
        if (server) {
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, "narada.local")))
            builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(arrayOf(KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth)))
        }
        // Client fixture intentionally has no SAN/EKU, matching AndroidKeyStore's client-only certificate profile.
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private)))
        return TransportIdentity(cert, pair.private, "TEST ONLY: exportable software key, not AndroidKeyStore")
    }
    fun document(identity: TransportIdentity) = TrustDocument(storeId = UUID.randomUUID().toString(), deviceId = identity.deviceId, fingerprint = identity.fingerprint, certificate = Base64.getEncoder().encodeToString(identity.certificate.encoded), enabled = true)
    fun code(server: TransportIdentity, endpoint: String = "127.0.0.1:12345", expires: Long = System.currentTimeMillis() + 120_000) = PairCode("narada-pairing", 1, UUID.randomUUID().toString(), sha256(UUID.randomUUID().toString().toByteArray()), endpoint, server.fingerprint, server.deviceId, "Test desktop", java.time.Instant.ofEpochMilli(expires).toString())
}