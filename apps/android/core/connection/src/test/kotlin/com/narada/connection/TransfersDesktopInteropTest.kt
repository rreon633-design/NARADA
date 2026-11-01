package com.narada.connection

import java.io.File
import java.net.Socket
import java.nio.file.Files
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

/** Actual unchanged Linux Rust agent + JSSE + real encrypted private files. Software test keys;
 * not AndroidKeyStore/Conscrypt, native SAF, process-kill or Windows qualification. */
class TransfersDesktopInteropTest {
    private lateinit var host:RealDesktop
    private lateinit var share:File
    private lateinit var privateDir:File
    private lateinit var store:MemoryTrust
    private lateinit var tls:TlsConnector
    private lateinit var pairing:PairingEngine
    private lateinit var files:FilesClient
    private lateinit var storage:TransferChunks
    private lateinit var downloads:DownloadEngine
    private lateinit var rootId:String
    private lateinit var peerId:String
    private val bytes=TransferSamples.bytes(TransferRules.CHUNK*2+19)
    private val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
    private var beforeChunk:suspend (Int)->Unit={}
    private var afterPrepare:suspend ()->Unit={}
    private var afterFinish:suspend ()->Unit={}
    private val chunkRequests=mutableListOf<Int>()
    private val gateway=PacedTransfersGateway(object:TransfersGateway {
        override suspend fun prepare(record:DownloadRecord):ManifestBundle = pairing.withActiveSession(record.peerId){p,s->TransfersClient(store,pairing).prepare(p,s,record)}.also{afterPrepare()}
        override suspend fun chunk(record:DownloadRecord,index:Int):ByteArray {beforeChunk(index);chunkRequests.add(index);return pairing.withActiveSession(record.peerId){p,s->TransfersClient(store,pairing).chunk(p,s,record,index)}}
        override suspend fun finish(record:DownloadRecord){pairing.withActiveSession(record.peerId){p,s->TransfersClient(store,pairing).finish(p,s,record)};afterFinish()}
        override fun cancel(){tls.closeAll()}
    })
    @Before fun setup()=runBlocking<Unit>{
        host=RealDesktop();share=Files.createTempDirectory("narada-p4-source-").toFile();privateDir=Files.createTempDirectory("narada-p4-private-").toFile()
        File(share,"ordinary.bin").writeBytes(bytes);File(share,"empty.bin").writeBytes(byteArrayOf());File(share,"నమూనా.bin").writeBytes(bytes)
        val root=host.call("register_root",buildJsonObject{put("path",share.path);put("label","Phase 4 disposable root");put("exclusions",buildJsonArray{})}).jsonObject
        rootId=root["root_id"]!!.jsonPrimitive.content;assertEquals("ready",root["state"]!!.jsonPrimitive.content)
        store=MemoryTrust();tls=TlsConnector(SocketSource{Socket()});pairing=PairingEngine(store,tls);files=FilesClient(store,pairing);host.enable()
        val code=PairCode.parse(host.call("start_pairing").jsonObject["code"]!!.jsonPrimitive.content);val pending=pairing.begin(code,"Android transfer JVM fixture");pairing.prove(pending.id)
        host.call("approve_pairing",buildJsonObject{put("attempt_id",pending.attemptId);put("permissions",Wire.json.encodeToJsonElement(Permissions(roots=listOf(RootGrant(rootId,listOf("browse","read"))))))})
        pairing.prove(pending.id);peerId=pairing.finish(pending.id,true).id
        pairing.withActiveSession(peerId){p,s->pairing.health(p,s)}
        openStorage();downloads=DownloadEngine(store,gateway,storage)
    }
    private fun openStorage(){storage=EncryptedChunkFiles(File(privateDir,"received").toPath(),object:ChunkCryptography{
        override fun seal(context:ChunkContext,clear:ByteArray)=TransferCipher.seal(context,key,clear)
        override fun unseal(context:ChunkContext,sealed:ByteArray)=TransferCipher.unseal(context,key,sealed)
    })}
    @After fun cleanup(){if(::tls.isInitialized)tls.closeAll();if(::host.isInitialized)host.close();if(::share.isInitialized)share.deleteRecursively();if(::privateDir.isInitialized)privateDir.deleteRecursively()}
    private suspend fun selected(name:String):RemoteEntry = pairing.withActiveSession(peerId){p,s->(files.query(p,s,FileQuery.Search(rootId,name)) as FileResult.Page).value.entries.single{it.name==name}}
    private suspend fun queue(name:String="ordinary.bin"):DownloadRecord {val e=selected(name);return downloads.enqueue(peerId,store.document.peer(peerId).attemptId,TransferSource(rootId,e.object_id,e.version),e.name,e.size.toLong(),true)}
    private fun changePermissions(caps:List<String>){val revision=host.snapshot()["peers"]!!.jsonArray.single().jsonObject["grant_revision"]!!.jsonPrimitive.int;host.call("update_peer_permissions",buildJsonObject{put("peer_id",store.key.deviceId);put("expected_revision",revision);put("permissions",Wire.json.encodeToJsonElement(Permissions(roots=listOf(RootGrant(rootId,caps)))))})}
    private suspend fun restartDesktop(){host.restart();pairing.setEndpoint(peerId,host.snapshot()["endpoint"]!!.jsonPrimitive.content)}
    private fun reopenPhone(){store=MemoryTrust(store.key,Wire.decode(Wire.encode(store.document,Wire.MAX_STORE),Wire.MAX_STORE));pairing=PairingEngine(store,tls);files=FilesClient(store,pairing);openStorage();downloads=DownloadEngine(store,gateway,storage)}
    private fun content(record:DownloadRecord):ByteArray {val output=java.io.ByteArrayOutputStream();for(i in 0 until record.manifest!!.chunk_count){val data=storage.read(store.document.storeId,record,i)!!;try{output.write(data)}finally{data.fill(0)}};return output.toByteArray()}
    @Test fun realBinaryChunksProduceAByteExactPrivateReceiptAndFinishAck()=runBlocking<Unit>{val r=downloads.run(queue().id);assertEquals(DownloadState.VERIFIED,r.state);assertArrayEquals(bytes,content(r));assertEquals(sha256(bytes),r.receipt!!.sha256);assertEquals(listOf(0,1,2),chunkRequests);downloads.finish(r.id);assertTrue(store.document.download(r.id).remoteFinished);assertArrayEquals(bytes,File(share,"ordinary.bin").readBytes())}
    @Test fun emptyOrdinaryFileNeedsNoBinaryRequestButGetsFullReceipt()=runBlocking<Unit>{val r=downloads.run(queue("empty.bin").id);assertTrue(chunkRequests.isEmpty());assertEquals(0L,r.receipt!!.size);assertEquals(sha256(byteArrayOf()),r.receipt.sha256);downloads.finish(r.id)}
    @Test fun unicodeManifestTupleMatchesTheActualRustDigest()=runBlocking<Unit>{val r=downloads.run(queue("నమూనా.bin").id);assertEquals("నమూనా.bin",r.manifest!!.name);r.manifest.verify(r.hashes);assertArrayEquals(bytes,content(r))}
    @Test fun realThirtyTwoHashPaginationAndPartialLastChunk()=runBlocking<Unit>{val large=TransferSamples.bytes(TransferRules.CHUNK*33+7);File(share,"paged.bin").writeBytes(large);host.call("reindex_root",buildJsonObject{put("root_id",rootId)});val r=downloads.run(queue("paged.bin").id);assertEquals(34,r.hashes.size);assertEquals(7,r.manifest!!.length(33));assertEquals(sha256(large),r.receipt!!.sha256);assertArrayEquals(large,content(r))}
    @Test fun sourceChangedBeforePrepareCannotSilentlySelectAnotherVersion()=runBlocking<Unit>{val r=queue();File(share,"ordinary.bin").writeBytes(byteArrayOf(8,9,10));expectFailure("STALE_VERSION"){runBlocking{downloads.run(r.id)}};assertNull(store.document.download(r.id).manifest);assertNull(store.document.download(r.id).receipt)}
    @Test fun immutableSnapshotResumesAcrossBothRepositoryAndAgentRestart()=runBlocking<Unit>{val r=queue();beforeChunk={if(it==1)fail("PEER_TIMEOUT")};expectFailure("PEER_TIMEOUT"){runBlocking{downloads.run(r.id)}};assertEquals(listOf(0),store.document.download(r.id).chunks);File(share,"ordinary.bin").writeBytes("new live bytes, same original object".toByteArray());restartDesktop();reopenPhone();downloads.recover();beforeChunk={};downloads.queueResume(r.id,true);val complete=downloads.run(r.id);assertArrayEquals(bytes,content(complete));assertEquals(1,chunkRequests.count{it==0});assertNotEquals(sha256(File(share,"ordinary.bin").readBytes()),complete.receipt!!.sha256)}
    @Test fun realGrantWithdrawalStopsNewChunksWithoutErasingReceivedProgress()=runBlocking<Unit>{val r=queue();beforeChunk={if(it==1){changePermissions(listOf("browse"));beforeChunk={}}};expectFailure("ACCESS_DENIED"){runBlocking{downloads.run(r.id)}};val saved=store.document.download(r.id);assertEquals(listOf(0),saved.chunks);assertNull(saved.receipt);assertNotNull(storage.read(store.document.storeId,saved,0));downloads.recover();changePermissions(listOf("browse","read"));downloads.queueResume(r.id,true);val complete=downloads.run(r.id);assertArrayEquals(bytes,content(complete))}
    @Test fun localRevocationBlocksResumeUnderTheOldPairing()=runBlocking<Unit>{val r=queue();beforeChunk={if(it==1)fail("PEER_TIMEOUT")};expectFailure{runBlocking{downloads.run(r.id)}};downloads.recover();pairing.revoke(peerId);downloads.queueResume(r.id,true);expectFailure("DEVICE_REVOKED"){runBlocking{downloads.run(r.id)}};assertNull(store.document.download(r.id).receipt);assertEquals(TrustStatus.REVOKED,store.document.peer(peerId).status)}
    @Test fun corruptedEncryptedCheckpointIsNotReplacedOnRestart()=runBlocking<Unit>{val r=queue();beforeChunk={if(it==1)fail("PEER_TIMEOUT")};expectFailure{runBlocking{downloads.run(r.id)}};val chunk=File(privateDir,"received/${store.document.storeId}/${r.id}/chunk-0.enc");val damaged=chunk.readBytes().apply{this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()};chunk.writeBytes(damaged);reopenPhone();downloads.recover();beforeChunk={};downloads.queueResume(r.id,true);expectFailure("LOCAL_COPY_CHANGED"){runBlocking{downloads.run(r.id)}};assertArrayEquals(damaged,chunk.readBytes());assertNull(store.document.download(r.id).receipt)}
    @Test fun lostPrepareReplyReusesTheSameDurableSourceIntentAfterRestart()=runBlocking<Unit>{val r=queue();afterPrepare={fail("PEER_TIMEOUT")};expectFailure("PEER_TIMEOUT"){runBlocking{downloads.run(r.id)}};assertNull(store.document.download(r.id).manifest);restartDesktop();reopenPhone();downloads.recover();afterPrepare={};downloads.queueResume(r.id,true);val complete=downloads.run(r.id);assertEquals(r.id,complete.id);assertEquals(1,store.document.downloads.size);assertArrayEquals(bytes,content(complete))}
    @Test fun lostFinishReplyRetainsSavedCopyAndRetriesOnlySameAcknowledgment()=runBlocking<Unit>{val r=downloads.run(queue().id);afterFinish={fail("PEER_TIMEOUT")};expectFailure("PEER_TIMEOUT"){runBlocking{downloads.finish(r.id)}};reopenPhone();downloads.recover();assertEquals(DownloadState.VERIFIED,store.document.download(r.id).state);afterFinish={};downloads.finish(r.id);assertTrue(store.document.download(r.id).remoteFinished);assertArrayEquals(bytes,content(r));assertEquals(3,chunkRequests.size)}
    @Test fun clockAgedRealSnapshotCannotBeReplacedOnExplicitResume()=runBlocking<Unit>{val r=queue();beforeChunk={if(it==1)fail("PEER_TIMEOUT")};expectFailure("PEER_TIMEOUT"){runBlocking{downloads.run(r.id)}};val manifest=store.document.download(r.id).manifest;host.ageSourceSnapshot(r.id);pairing.setEndpoint(peerId,host.snapshot()["endpoint"]!!.jsonPrimitive.content);reopenPhone();downloads.recover();beforeChunk={};downloads.queueResume(r.id,true);expectFailure("TRANSFER_EXPIRED"){runBlocking{downloads.run(r.id)}};val stopped=store.document.download(r.id);assertEquals(r.id,stopped.id);assertEquals(manifest,stopped.manifest);assertEquals(listOf(0),stopped.chunks);assertNull(stopped.receipt);assertEquals(listOf(0),chunkRequests);assertEquals(1,store.document.downloads.size)}

}
