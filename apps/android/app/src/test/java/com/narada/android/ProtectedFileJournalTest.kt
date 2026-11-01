package com.narada.android

import com.narada.android.connection.*
import com.narada.connection.*
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real AES-GCM codec + repository, software fixture keys. Not an AndroidKeyStore execution claim. */
class ProtectedFileJournalTest {
    private class Fixture {
        val anchor=TestAnchor();val keys=TestKeys();val blob=TestBlob()
        fun open()=ProtectedConnectionsRepository(anchor,keys,blob)
        suspend fun setup():Pair<TrustDocument,FileOperation> {
            val repo=open();repo.initialize();val host=AndroidTestIdentities.create(true)
            val peer=TrustedPeer(host.deviceId,host.fingerprint,Base64.getEncoder().encodeToString(host.certificate.encoded),"Fixture desktop","linux","192.168.1.2:12345",UUID.randomUUID().toString(),UUID.randomUUID().toString(),System.currentTimeMillis()+60000,TrustStatus.ACTIVE)
            val doc=repo.mutate{it.copy(enabled=true,preferredPeer=peer.id,peers=listOf(peer))}
            val input=FileMutationInput(UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),"a".repeat(64),FileAction("rename","PRIVATE-FILENAME-TEST.jpg"))
            return doc to FileOperation(peer.id,peer.attemptId,input,System.currentTimeMillis())
        }
    }
    private fun failure(code:String,block:suspend()->Unit)=runBlocking {
        try{block();org.junit.Assert.fail("Expected refusal")}catch(f:ConnectionFailure){assertEquals(code,f.code)}
    }
    @Test fun pendingIntentIsEncryptedAndSurvivesRepositoryRecreation()=runBlocking<Unit> {
        val f=Fixture();val(doc,op)=f.setup();f.open().mutate{it.copy(fileOperations=listOf(op))}
        val encoded=f.blob.bytes!!;assertFalse(String(encoded).contains("PRIVATE-FILENAME-TEST"));assertFalse(String(encoded).contains(op.request.operationId))
        val reopened=f.open().read()!!;assertEquals(listOf(op),reopened.fileOperations);assertEquals(doc.deviceId,reopened.deviceId);assertEquals(1,f.keys.creations)
    }
    @Test fun legacyPhaseTwoPayloadMigratesWithoutRekeyOrSilentReset()=runBlocking<Unit> {
        val f=Fixture();val(doc,op)=f.setup()
        val old=Wire.json.encodeToJsonElement(doc).jsonObject
        val legacy=JsonObject(old.filterKeys{it!="fileOperations" && it!="downloads"}+mapOf("version" to JsonPrimitive(1),"peers" to JsonArray(old["peers"]!!.jsonArray.map { JsonObject(it.jsonObject.filterKeys{key->key!="remoteOperations"}) })))
        f.blob.write(f.keys.seal(doc.storeId,legacy.toString().toByteArray()))
        assertEquals(1,f.open().read()!!.version)
        val upgraded=f.open().mutate{it.copy(fileOperations=listOf(op))}
        assertEquals(6,upgraded.version);assertEquals(doc.fingerprint,upgraded.fingerprint);assertEquals(doc.certificate,upgraded.certificate);assertEquals(1,f.keys.creations)
        assertEquals(op,f.open().read()!!.fileOperations.single())
    }
    @Test fun failedLegacyMigrationLeavesOriginalSealedBytesAndKeys()=runBlocking<Unit> {
        val f=Fixture();val(doc,op)=f.setup();f.blob.write(f.keys.seal(doc.storeId,Wire.encode(doc.copy(version=1),Wire.MAX_STORE)))
        val old=f.blob.bytes!!.clone();f.blob.failWrite=true
        failure("STORAGE_UNAVAILABLE"){f.open().mutate{it.copy(fileOperations=listOf(op))}}
        assertArrayEquals(old,f.blob.bytes);assertEquals(1,f.open().read()!!.version);assertEquals(1,f.keys.creations)
    }
    @Test fun failedReceiptPersistenceLeavesThePendingOperation()=runBlocking<Unit> {
        val f=Fixture();val(_,op)=f.setup();f.open().mutate{it.copy(fileOperations=listOf(op))};val old=f.blob.bytes!!.clone();f.blob.failWrite=true
        val receipt=FileReceipt(op.request.operationId,"committed","rename",op.request.objectId,"b".repeat(64),null)
        failure("STORAGE_UNAVAILABLE"){f.open().mutate{it.copy(fileOperations=listOf(op.copy(state=FileOperationState.COMMITTED,receipt=receipt)))}}
        assertArrayEquals(old,f.blob.bytes);assertEquals(FileOperationState.UNCONFIRMED,f.open().read()!!.fileOperations.single().state)
    }
    @Test fun tamperedJournalCannotProduceReadableNamesOrFreshIdentity()=runBlocking<Unit> {
        val f=Fixture();val(_,op)=f.setup();f.open().mutate{it.copy(fileOperations=listOf(op))};f.blob.bytes=f.blob.bytes!!.clone().apply{this[lastIndex]=(this[lastIndex].toInt() xor 0x10).toByte()}
        val damaged=f.blob.bytes!!.clone();failure("TRUST_STORE_INVALID"){f.open().initialize()};assertArrayEquals(damaged,f.blob.bytes);assertEquals(1,f.keys.creations)
    }
    @Test fun unknownFuturePayloadAndMalformedIntentDoNotGetReset()=runBlocking<Unit> {
        val f=Fixture();val(doc,op)=f.setup();val future=Wire.encode(doc.copy(version=99),Wire.MAX_STORE);f.blob.write(f.keys.seal(doc.storeId,future))
        failure("TRUST_STORE_INVALID"){f.open().read()};assertEquals(1,f.keys.creations)
        f.blob.write(f.keys.seal(doc.storeId,Wire.encode(doc.copy(fileOperations=listOf(op.copy(request=op.request.copy(objectId="content://provider/private")))),Wire.MAX_STORE)))
        failure("TRUST_STORE_INVALID"){f.open().read()};assertEquals(1,f.keys.creations)
    }
}
