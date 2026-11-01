package com.narada.connection

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import javax.crypto.KeyGenerator

class ProtocolTest {
    @Test fun acceptsCurrentDesktopInvitationAndRfc3339Offset() {
        val code = TestIdentities.code(TestIdentities.create(true))
        assertEquals(code.device_id, PairCode.parse(Wire.json.encodeToString(PairCode.serializer(), code).replace("Z\"", "+00:00\"")).device_id)
    }
    @Test fun invitationSecretHasRedactedDebugRepresentation() { val code=TestIdentities.code(TestIdentities.create(true)); assertFalse(code.toString().contains(code.token)); assertFalse(Claim(invitation_id=code.invitation_id,attempt_id=UUID.randomUUID().toString(),token=code.token,certificate="public",name="Phone").toString().contains(code.token)) }
    @Test fun duplicateKeysIncludingEscapesAreRejected() { expectFailure("DUPLICATE_FIELD") { Wire.decode<Hello>("{\"version\":1,\"\\u0076ersion\":1,\"capabilities\":[]}".toByteArray()) } }
    @Test fun quotedNumericAndBooleanFieldsCannotBeCoerced() { expectFailure { Wire.decode<Hello>("{\"version\":\"1\",\"capabilities\":[\"connection.health\",\"connection.permissions\"]}".toByteArray()) }; expectFailure { Wire.decode<Permissions>("{\"chat\":\"true\",\"attachment\":false,\"roots\":[]}".toByteArray()) } }
    @Test fun unknownFieldsAreRejected() { expectFailure { Wire.decode<Hello>("{\"version\":1,\"capabilities\":[],\"admin\":true}".toByteArray()) } }
    @Test fun nestingIsBounded() { expectFailure { StrictJson.check("[".repeat(14)+"0"+"]".repeat(14)) } }
    @Test fun invalidUtf8IsRejectedBeforeParsing() { expectFailure { Wire.decode<Hello>(byteArrayOf(0xc3.toByte(),0x28)) } }
    @Test fun codeByteSizeIsBounded() { expectFailure("BODY_TOO_LARGE") { PairCode.parse(" ".repeat(4097)) } }
    @Test fun expiredCodeIsNotMadeFresh() { expectFailure("INVITATION_EXPIRED") { TestIdentities.code(TestIdentities.create(true),expires=System.currentTimeMillis()-1).validate() } }
    @Test fun excessiveClockSkewIsRejected() { expectFailure("CLOCK_SKEW") { TestIdentities.code(TestIdentities.create(true),expires=System.currentTimeMillis()+200_000).validate() } }
    @Test fun privateLiteralAndLoopbackEndpointsAreCanonical() { for (value in listOf("10.1.2.3:1234","https://172.16.0.1:65535","192.168.0.1:1","127.0.0.1:443")) assertNotNull(Endpoint.parse(value)) }
    @Test fun dnsPublicMetadataMulticastAndUrlTricksAreRejected() { for (value in listOf("example.com:443","8.8.8.8:443","169.254.169.254:80","224.0.0.251:5353","0.0.0.0:80","192.168.1.1:0","192.168.1.1:65536","192.168.01.1:443","[::1]:443","u:p@192.168.1.1:443","192.168.1.1:443/a","192.168.1.1:443?x","192.168.1.1:443\r\nX:y","https://https://192.168.1.1:443")) expectFailure { Endpoint.parse(value) } }
    @Test fun helloRejectsDowngrades() { expectFailure("PROTOCOL_INCOMPATIBLE") { Hello(version=0).validate() }; expectFailure("CAPABILITIES_INCOMPATIBLE") { Hello(capabilities=listOf("connection.health")).validate() } }
    @Test fun capabilitiesCannotBeDuplicated() { expectFailure { Hello(capabilities=listOf("connection.health","connection.permissions","connection.health")).validate() } }
    @Test fun namesCannotSpoofWithControlsOrDirectionOverrides() { for (name in listOf("", " ", "a\n", "owner\u202eexe", "a\u2066b", "x".repeat(49))) expectFailure { validName(name) }; validName("నా ఫోన్") }
    @Test fun idsMustBeCanonicalAndFullLength() { expectFailure { validHash("a".repeat(63)) }; expectFailure { canonicalUuid("1-1-1-1-1") } }
    @Test fun grantsAreScopedUniqueAndNeverOwnerAuthority() { val id=UUID.randomUUID().toString(); Permissions(roots=listOf(RootGrant(id,listOf("browse","read")))).validate(); for (caps in listOf(listOf("read","read"),listOf("admin"),listOf("chat"))) expectFailure { Permissions(roots=listOf(RootGrant(id,caps))).validate() } }
    @Test fun duplicateAndExcessiveRootsAreRejected() { val root=RootGrant(UUID.randomUUID().toString(),listOf("read")); expectFailure { Permissions(roots=listOf(root,root)).validate() }; expectFailure { Permissions(roots=(1..9).map { RootGrant(UUID.randomUUID().toString(),emptyList()) }).validate() } }
    @Test fun healthHasKnownOperationsAndExactIdentity() { val health=Health(Hello(),"a".repeat(64),Permissions(),1,1,listOf("health","permissions","files.v1","transfers.v1","chat.olm.v1","attachments.age.v1","sync.v1")); health.validate("a".repeat(64)); expectFailure { health.copy(operations=listOf("health","shutdown")).validate(health.device_id) }; expectFailure("IDENTITY_REJECTED") { health.validate("b".repeat(64)) } }
    @Test fun retryBudgetAndDelayAreBounded() { assertEquals(30_250L,retryDelay(6,250)); expectFailure { retryDelay(7,0) }; assertFalse(transient("IDENTITY_REJECTED")); assertTrue(transient("PEER_TIMEOUT")) }
    @Test fun currentP256ClientCertificateAndPossessionAreValid() { val key=TestIdentities.create(); Certificates.parse(key.certificate.encoded); Certificates.verifyKey(key) }
    @Test fun missingServerSanIsRejectedEvenForSelfSignedKey() { expectFailure("INVALID_CERTIFICATE") { Certificates.parse(TestIdentities.create().certificate.encoded,true) } }
    @Test fun wrongCurveExpiredAndTrailingDerAreRejected() { expectFailure { Certificates.parse(TestIdentities.create(curve="secp384r1").certificate.encoded) }; expectFailure { Certificates.parse(TestIdentities.create(expires=System.currentTimeMillis()-1).certificate.encoded) }; expectFailure { Certificates.parse(TestIdentities.create().certificate.encoded+0) } }
    @Test fun wrongPrivateKeyCannotUseCertificate() { val a=TestIdentities.create(); val b=TestIdentities.create(); expectFailure("IDENTITY_CHANGED") { Certificates.verifyKey(TransportIdentity(a.certificate,b.key,"test")) } }
    @Test fun aesEnvelopeAuthenticatesNoncePayloadAndStoreContext() { val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(); val id=UUID.randomUUID().toString();val plain="private trust".toByteArray();val sealed=TrustCipher.seal(id,key,plain);assertArrayEquals(plain,TrustCipher.unseal(id,key,sealed)); assertFalse(sealed.contentEquals(TrustCipher.seal(id,key,plain)));for (other in listOf(sealed.copyOf().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() },sealed.copyOf(sealed.size-1))) try { TrustCipher.unseal(id,key,other);org.junit.Assert.fail("tamper accepted") } catch (_:Exception) {} ;try { TrustCipher.unseal(UUID.randomUUID().toString(),key,sealed);org.junit.Assert.fail("wrong context accepted") }catch(_:Exception){} }
    @Test fun wrongSealKeyCannotDecryptAndOversizedInputIsRejected() { val key=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey();val other=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey();val id=UUID.randomUUID().toString();try { TrustCipher.unseal(id,other,TrustCipher.seal(id,key,byteArrayOf(1)));org.junit.Assert.fail("wrong key accepted") }catch(_:Exception){};expectFailure("BODY_TOO_LARGE") { TrustCipher.seal(id,key,ByteArray(Wire.MAX_STORE+1)) } }
}
