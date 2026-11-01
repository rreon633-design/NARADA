package com.narada.android

import com.narada.android.connection.*
import com.narada.connection.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtectedDownloadJournalTest {
    private class Fixture {
        val anchor=TestAnchor();val keys=TestKeys();val blob=TestBlob()
        fun open()=ProtectedConnectionsRepository(anchor,keys,blob)
        suspend fun setup():Pair<TrustDocument,DownloadRecord>{val repo=open();repo.initialize();val peer=TransferTestData.peer();val r=TransferTestData.verified(peer);return repo.mutate{it.copy(peers=listOf(peer),preferredPeer=peer.id,downloads=listOf(r))} to r}
    }
    private fun failure(code:String?=null,body:suspend ()->Unit)=runBlocking{try{body();org.junit.Assert.fail("Expected failure")}catch(e:ConnectionFailure){if(code!=null)assertEquals(code,e.code)}}
    @Test fun privateReceiptAndUncertainDestinationAreSealedAndReopened()=runBlocking<Unit>{val f=Fixture();val(doc,r)=f.setup();val e=ExportRecord(UUID.randomUUID().toString(),"private-copy.bin",r.createdAt,ExportState.UNCERTAIN,"content://private.provider/tree/root","content://private.provider/document/new");val saved=f.open().mutate{it.replaceDownload(r.copy(exports=listOf(e)))};assertEquals(saved,f.open().read());val encrypted=f.blob.bytes!!.toString(Charsets.ISO_8859_1);listOf(r.name,e.name,"private.provider",r.manifest!!.manifest_id).forEach{assertFalse(encrypted.contains(it))};assertEquals(doc.fingerprint,saved.fingerprint);assertEquals(1,f.keys.creations)}
    @Test fun payloadTwoMigrationKeepsOwnershipKeysAndExistingEpochs()=runBlocking<Unit>{val f=Fixture();val(doc,r)=f.setup();val old=Wire.json.encodeToJsonElement(doc.copy(version=2,downloads=emptyList())).jsonObject;val json=JsonObject(old.filterKeys{it!="downloads"});f.blob.write(f.keys.seal(doc.storeId,json.toString().toByteArray()));assertEquals(2,f.open().read()!!.version);val migrated=f.open().mutate{it.copy(downloads=listOf(r))};assertEquals(6,migrated.version);assertEquals(doc.peers,migrated.peers);assertEquals(doc.storeId,migrated.storeId);assertEquals(doc.fingerprint,migrated.fingerprint);assertEquals(1,f.keys.creations)}
    @Test fun missingOrChangedCheckpointCannotBeWrittenAsAVerifiedReceipt()=runBlocking<Unit>{val f=Fixture();val(_,r)=f.setup();val before=f.blob.bytes!!.clone();failure("TRUST_STORE_INVALID"){f.open().mutate{it.replaceDownload(r.copy(chunks=emptyList()))}};assertArrayEquals(before,f.blob.bytes)}
    @Test fun failedProtectedCheckpointWriteRetainsThePriorAggregate()=runBlocking<Unit>{val f=Fixture();val(_,r)=f.setup();val before=f.blob.bytes!!.clone();f.blob.failWrite=true;failure("STORAGE_UNAVAILABLE"){f.open().mutate{it.replaceDownload(r.copy(remoteFinished=true))}};assertArrayEquals(before,f.blob.bytes);assertFalse(f.open().read()!!.download(r.id).remoteFinished)}
    @Test fun futureDownloadEnvelopeIsPreservedNotResetOrRekeyed()=runBlocking<Unit>{val f=Fixture();val(doc,_)=f.setup();f.blob.write(f.keys.seal(doc.storeId,Wire.encode(doc.copy(version=7),Wire.MAX_STORE)));val saved=f.blob.bytes!!.clone();failure("TRUST_STORE_INVALID"){f.open().read()};assertArrayEquals(saved,f.blob.bytes);assertEquals(1,f.keys.creations)}
    @Test fun revocationRetainsTheAlreadyReceivedPrivateReceipt()=runBlocking<Unit>{val f=Fixture();val(_,r)=f.setup();val saved=f.open().mutate{d->val p=d.peer(r.peerId);d.replace(p.copy(status=TrustStatus.REVOKED,remotePermissions=Permissions(),remoteOperations=emptyList(),localEpoch=p.localEpoch+1)).copy(preferredPeer=null)};assertEquals(r,f.open().read()!!.download(r.id));assertEquals(TrustStatus.REVOKED,saved.peer(r.peerId).status)}
    @Test fun boundedFullJournalsFitTheSealedStoreAndStrictParser()=runBlocking<Unit>{
        val f=Fixture();f.open().initialize();val peers=List(16){TransferTestData.peer().copy(remotePermissions=Permissions(roots=List(8){i->RootGrant(if(i==0)TransferTestData.root else UUID.randomUUID().toString(),listOf("browse","read","create","modify","trash","sync_read"))}))};val p=peers.first()
        val rows=List(4){val count=128;val size=count.toLong()*TransferRules.CHUNK;val hashes=List(count){"a".repeat(64)};val name="n".repeat(180);val m=TransferManifest(1,"0".repeat(64),TransferTestData.source.expected_version,name,size.toString(),TransferRules.CHUNK,count,"b".repeat(64));val manifest=m.copy(manifest_id=m.digest(hashes));val uri="content://provider/"+"x".repeat(2029);DownloadRecord(UUID.randomUUID().toString(),p.id,p.attemptId,TransferTestData.source,name,size,1,DownloadState.VERIFIED,manifest=manifest,hashes=hashes,chunks=hashes.indices.toList(),receipt=PrivateReceipt(manifest.manifest_id,m.sha256,size,2),exports=List(2){ExportRecord(UUID.randomUUID().toString(),name,2,ExportState.UNCERTAIN,uri,uri)})}
        val operations=List(32){FileOperation(p.id,p.attemptId,FileMutationInput(TransferTestData.root,UUID.randomUUID().toString(),TransferTestData.objectId,TransferTestData.source.expected_version,FileAction("rename","r".repeat(180))),1,FileOperationState.UNCONFIRMED)}
        val saved=f.open().mutate{it.copy(peers=peers,preferredPeer=p.id,fileOperations=operations,downloads=rows)}
        assertEquals(TransferRules.QUOTA,saved.downloads.sumOf{it.size});assertTrue(Wire.encode(saved,Wire.MAX_STORE).size<Wire.MAX_STORE);assertEquals(saved,f.open().read());assertEquals(32,saved.fileOperations.size);assertEquals(4,saved.downloads.size)
    }
}
