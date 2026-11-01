package com.narada.android

import com.narada.connection.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object TransferTestData {
    val root="11111111-1111-4111-8111-111111111111"
    val objectId="22222222-2222-4222-8222-222222222222"
    val source=TransferSource(root,objectId,"a".repeat(64))
    val bytes=ByteArray(TransferRules.CHUNK*2+19){((it*7+3)%251).toByte()}
    fun bundle(name:String="ordinary.bin"):ManifestBundle {
        val hashes=bytes.asList().chunked(TransferRules.CHUNK).map{sha256(it.toByteArray())}
        val m=TransferManifest(1,"0".repeat(64),source.expected_version,name,bytes.size.toString(),TransferRules.CHUNK,hashes.size,sha256(bytes))
        return ManifestBundle(m.copy(manifest_id=m.digest(hashes)),hashes)
    }
    fun peer():TrustedPeer {val host=AndroidTestIdentities.create(true);return TrustedPeer(host.deviceId,host.fingerprint,Base64.getEncoder().encodeToString(host.certificate.encoded),"Transfer test desktop","linux","192.168.0.2:12345",UUID.randomUUID().toString(),UUID.randomUUID().toString(),System.currentTimeMillis()+120000,TrustStatus.ACTIVE,remoteRevision=1,remoteEpoch=1,remotePermissions=Permissions(roots=listOf(RootGrant(root,listOf("browse","read")))),remoteOperations=listOf("health","permissions","files.v1","transfers.v1"))}
    fun queued(peer:TrustedPeer,name:String="ordinary.bin")=DownloadRecord(UUID.randomUUID().toString(),peer.id,peer.attemptId,source,name,bytes.size.toLong(),System.currentTimeMillis())
    fun verified(peer:TrustedPeer,name:String="ordinary.bin"):DownloadRecord {val r=queued(peer,name);val b=bundle(name);return r.copy(state=DownloadState.VERIFIED,manifest=b.manifest,hashes=b.hashes,chunks=b.hashes.indices.toList(),receipt=PrivateReceipt(b.manifest.manifest_id,b.manifest.sha256,r.size,r.createdAt))}
    fun choice(peer:TrustedPeer)=DownloadChoice(peer.id,peer.attemptId,source,"ordinary.bin",bytes.size.toLong())
}
class TestTransferChunks:TransferChunks {
    val data=ConcurrentHashMap<Pair<String,Int>,ByteArray>()
    override fun reserve(storeId:String,record:DownloadRecord){}
    override fun read(storeId:String,record:DownloadRecord,index:Int)=data[record.id to index]?.clone()
    override fun write(storeId:String,record:DownloadRecord,index:Int,clear:ByteArray){data[record.id to index]=clear.clone()}
    override fun remove(storeId:String,record:DownloadRecord){data.keys.filter{it.first==record.id}.forEach{data.remove(it)}}
    fun seed(record:DownloadRecord){record.hashes.indices.forEach{i->data[record.id to i]=TransferTestData.bytes.copyOfRange(i*TransferRules.CHUNK,minOf((i+1)*TransferRules.CHUNK,TransferTestData.bytes.size))}}
}
