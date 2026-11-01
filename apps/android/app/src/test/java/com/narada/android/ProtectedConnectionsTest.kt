package com.narada.android

import com.narada.android.connection.*
import com.narada.android.data.ConnectionAnchor
import com.narada.connection.*
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TestAnchor : EnrollmentAnchor {
    var marker: ConnectionAnchor? = null
    var failBind = false
    override suspend fun read() = marker
    override suspend fun reserve(id: String) { check(marker==null);marker=ConnectionAnchor(storeId=id,status="PREPARING",deviceId=null,fingerprint=null) }
    override suspend fun bind(id: String, deviceId: String, fingerprint: String) { if(failBind) throw java.io.IOException();check(marker?.storeId==id);marker=marker!!.copy(status="READY",deviceId=deviceId,fingerprint=fingerprint) }
}
class TestKeys : ConnectionKeys {
    var id: String? = null
    var key: TransportIdentity? = null
    var aes: SecretKey? = null
    var creations=0
    override fun hasOwnedAliases() = id!=null
    override fun create(storeId: String) { check(id==null);id=storeId;creations++;key=AndroidTestIdentities.create();aes=KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    override fun identity(storeId: String): TransportIdentity { if(id!=storeId || key==null || aes==null) throw ConnectionFailure("IDENTITY_KEY_MISSING");return key!! }
    override fun seal(storeId: String, plaintext: ByteArray) = TrustCipher.seal(storeId,aes!!,plaintext)
    override fun unseal(storeId: String, ciphertext: ByteArray) = TrustCipher.unseal(storeId,aes!!,ciphertext)
}
class TestBlob : ProtectedBlob {
    var bytes: ByteArray? = null
    var failWrite=false
    override fun hasEvidence() = bytes!=null
    override fun read() = bytes?.clone() ?: throw java.io.FileNotFoundException()
    override fun write(bytes: ByteArray) { if(failWrite) throw java.io.IOException();this.bytes=bytes.clone() }
}
class ProtectedConnectionsTest {
    private class Fixture {
        val anchor=TestAnchor();val keys=TestKeys();val blob=TestBlob()
        fun repository() = ProtectedConnectionsRepository(anchor,keys,blob)
    }
    private fun failure(code: String?=null, block: suspend ()->Unit) = runBlocking {
        try { block();org.junit.Assert.fail("Expected fail-closed result") } catch(f:ConnectionFailure){if(code!=null)assertEquals(code,f.code)}
    }
    @Test fun startupReadDoesNotGenerateAnIdentity() = runBlocking<Unit> { val f=Fixture();assertNull(f.repository().read());assertEquals(0,f.keys.creations) }
    @Test fun explicitEnrollmentPersistsAnchorAndSealedDocument() = runBlocking<Unit> { val f=Fixture();val d=f.repository().initialize();assertEquals("READY",f.anchor.marker!!.status);assertEquals(d.deviceId,f.anchor.marker!!.deviceId);assertFalse(String(f.blob.bytes!!).contains(d.deviceId));assertEquals(d,f.repository().read());assertFalse(d.enabled) }
    @Test fun reopeningUsesSameKeyWithoutRotation() = runBlocking<Unit> { val f=Fixture();val first=f.repository().initialize();assertEquals(first,f.repository().initialize());assertEquals(1,f.keys.creations) }
    @Test fun missingBlobWithAnAnchorBlocksNewKeys() = runBlocking<Unit> { val f=Fixture();f.repository().initialize();f.blob.bytes=null;failure("TRUST_STORE_MISSING"){f.repository().initialize()};assertEquals(1,f.keys.creations) }
    @Test fun missingEcKeyDoesNotRecreateIdentity() = runBlocking<Unit> { val f=Fixture();f.repository().initialize();f.keys.key=null;failure("IDENTITY_KEY_MISSING"){f.repository().initialize()};assertEquals(1,f.keys.creations) }
    @Test fun missingSealKeyDoesNotFallbackToPlaintext() = runBlocking<Unit> { val f=Fixture();f.repository().initialize();f.keys.aes=null;failure("IDENTITY_KEY_MISSING"){f.repository().read()};assertEquals(1,f.keys.creations) }
    @Test fun missingAnchorWithExistingCiphertextIsBlocked() = runBlocking<Unit> { val f=Fixture();f.repository().initialize();f.anchor.marker=null;failure("IDENTITY_ANCHOR_MISSING"){f.repository().initialize()};assertEquals(1,f.keys.creations) }
    @Test fun orphanOwnedKeysWithoutFilesAreNotReplaced() = runBlocking<Unit> { val f=Fixture();f.keys.create(UUID.randomUUID().toString());failure("IDENTITY_ANCHOR_MISSING"){f.repository().initialize()};assertEquals(1,f.keys.creations) }
    @Test fun corruptCiphertextIsPreserved() = runBlocking<Unit> { val f=Fixture();f.repository().initialize();val damaged=f.blob.bytes!!.clone().apply { this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte() };f.blob.bytes=damaged;failure("TRUST_STORE_INVALID"){f.repository().read()};assertArrayEquals(damaged,f.blob.bytes) }
    @Test fun wrongAesKeyIsNotTreatedAsANewProfile() = runBlocking<Unit> { val f=Fixture();f.repository().initialize();val saved=f.blob.bytes!!.clone();f.keys.aes=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey();failure("TRUST_STORE_INVALID"){f.repository().read()};assertArrayEquals(saved,f.blob.bytes) }
    @Test fun identityAnchorCannotBeSubstituted() = runBlocking<Unit> { val f=Fixture();f.repository().initialize();f.anchor.marker=f.anchor.marker!!.copy(deviceId="a".repeat(64));failure("IDENTITY_CHANGED"){f.repository().read()} }
    @Test fun completeEncryptedEnrollmentCanFinishAfterAnchorCommitFailure() = runBlocking<Unit> { val f=Fixture();f.anchor.failBind=true;failure("STORAGE_UNAVAILABLE"){f.repository().initialize()};assertEquals("PREPARING",f.anchor.marker!!.status);f.anchor.failBind=false;assertNotNull(f.repository().read());assertEquals("READY",f.anchor.marker!!.status);assertEquals(1,f.keys.creations) }
    @Test fun incompleteEnrollmentDoesNotDiscardPartialState() = runBlocking<Unit> { val f=Fixture();f.blob.failWrite=true;failure("STORAGE_UNAVAILABLE"){f.repository().initialize()};assertEquals("PREPARING",f.anchor.marker!!.status);f.blob.failWrite=false;failure("TRUST_STORE_MISSING"){f.repository().initialize()};assertEquals(1,f.keys.creations) }
    @Test fun failedMutationNeverReturnsUnsavedSuccess() = runBlocking<Unit> { val f=Fixture();val d=f.repository().initialize();val bytes=f.blob.bytes!!.clone();f.blob.failWrite=true;failure("STORAGE_UNAVAILABLE"){f.repository().mutate{it.copy(enabled=true)}};assertArrayEquals(bytes,f.blob.bytes);assertEquals(d,f.repository().read()) }
    @Test fun concurrentMutationsSerializeTheirGeneration() = runBlocking<Unit> { val f=Fixture();val r=f.repository();r.initialize();coroutineScope { (1..20).map { launch { r.mutate { d->d.copy(enabled=!d.enabled) } } }.joinAll() };assertEquals(21L,r.read()!!.generation);assertFalse(r.read()!!.enabled) }
    @Test fun mutationCannotReplaceTheIdentityDescriptor() = runBlocking<Unit> { val f=Fixture();val r=f.repository();r.initialize();failure("IDENTITY_CHANGED"){r.mutate{it.copy(fingerprint="a".repeat(64))}} }
}
