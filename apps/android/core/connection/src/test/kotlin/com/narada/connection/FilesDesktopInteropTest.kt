package com.narada.connection

import java.io.File
import java.net.Socket
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*

/** Current unmodified Rust agent, real pinned TLS/JSON/file IO; JSSE software phone fixtures, NOT an Android device or Windows qualification. */
class FilesDesktopInteropTest {
    private lateinit var host:RealDesktop
    private lateinit var share:File
    private lateinit var store:MemoryTrust
    private lateinit var connector:TlsConnector
    private lateinit var engine:PairingEngine
    private lateinit var client:FilesClient
    private lateinit var rootId:String
    private lateinit var peer:TrustedPeer
    @Before fun setup()=runBlocking<Unit> {
        host=RealDesktop();share=Files.createTempDirectory("narada-phase3-shared-").toFile()
        repeat(20){File(share,"note-${it.toString().padStart(2,'0')}.txt").writeText("Disposable fixture $it")}
        File(share,"photo.jpg").writeBytes(FileSamples.jpeg())
        File(share,"active.svg").writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>not executed</script></svg>")
        File(share,"Documents").mkdir();File(share,"Documents/nested.txt").writeText("nested fixture")
        File(share,"excluded").mkdir();File(share,"excluded/private.txt").writeText("not granted")
        File(share,".hidden.txt").writeText("not catalogued")
        val root=host.call("register_root",buildJsonObject{put("path",share.path);put("label","Phase 3 shared fixture");put("exclusions",buildJsonArray{add("excluded")})}).jsonObject
        rootId=root["root_id"]!!.jsonPrimitive.content
        assertEquals("ready",root["state"]!!.jsonPrimitive.content)
        store=MemoryTrust();connector=TlsConnector(SocketSource{Socket()});engine=PairingEngine(store,connector);client=FilesClient(store,engine);host.enable()
        val code=PairCode.parse(host.call("start_pairing").jsonObject["code"]!!.jsonPrimitive.content)
        val pending=engine.begin(code,"Android files JVM fixture");engine.prove(pending.id)
        host.call("approve_pairing",buildJsonObject{put("attempt_id",pending.attemptId);put("permissions",Wire.json.encodeToJsonElement(Permissions(roots=listOf(RootGrant(rootId,FileSamples.capabilities)))))})
        engine.prove(pending.id);peer=engine.finish(pending.id,true)
    }
    @After fun close() {
        if(::connector.isInitialized)connector.closeAll()
        if(::host.isInitialized)host.close()
        if(::share.isInitialized)share.deleteRecursively() // Disposable fixture only, not user content.
    }
    private suspend fun query(q:FileQuery)=engine.withActiveSession(peer.id){p,s->client.query(p,s,q)}
    private suspend fun search(name:String)=(query(FileQuery.Search(rootId,name)) as FileResult.Page).value
    private suspend fun mutate(entry:RemoteEntry,action:FileAction)=engine.withActiveSession(peer.id){p,s->client.mutate(p,s,rootId,entry.object_id,entry.version,action,true)}
    private fun permissions(caps:List<String>) {
        val revision=host.snapshot()["peers"]!!.jsonArray.single().jsonObject["grant_revision"]!!.jsonPrimitive.int
        host.call("update_peer_permissions",buildJsonObject{put("peer_id",store.key.deviceId);put("expected_revision",revision);put("permissions",Wire.json.encodeToJsonElement(Permissions(roots=listOf(RootGrant(rootId,caps)))))})
    }
    private fun reindex() { host.call("reindex_root",buildJsonObject{put("root_id",rootId)}) }
    @Test fun realGrantedRootsBrowseSearchPaginationAndBreadcrumbs()=runBlocking<Unit> {
        val roots=(query(FileQuery.Roots) as FileResult.Roots).value
        assertEquals(listOf(rootId),roots.roots.map{it.root_id});assertTrue(roots.roots.single().capabilities.contains("browse"))
        val first=(query(FileQuery.Browse(rootId)) as FileResult.Page).value;assertEquals(16,first.entries.size);assertNotNull(first.next_cursor)
        val next=(query(FileQuery.Browse(rootId,cursor=first.next_cursor)) as FileResult.Page).value
        assertTrue(first.entries.map{it.object_id}.intersect(next.entries.map{it.object_id}.toSet()).isEmpty())
        assertFalse((first.entries+next.entries).any{it.name=="excluded"||it.name.startsWith('.')})
        val folder=first.entries.single{it.name=="Documents"}
        val nested=(query(FileQuery.Browse(rootId,folder.object_id)) as FileResult.Page).value
        assertEquals("nested.txt",nested.entries.single().name);assertEquals(folder.object_id,nested.breadcrumbs.last().object_id)
        assertEquals(20,search("note-").indexed_matches.toInt())
    }
    @Test fun allFourServerSortOrdersAndEmptySearchAreReal()=runBlocking<Unit> {
        val asc=(query(FileQuery.Search(rootId,"note-",sort=FileSort.NAME)) as FileResult.Page).value
        val desc=(query(FileQuery.Search(rootId,"note-",sort=FileSort.NAME_DESC)) as FileResult.Page).value
        assertEquals("note-00.txt",asc.entries.first().name);assertEquals("note-19.txt",desc.entries.first().name)
        assertEquals(16,(query(FileQuery.Search(rootId,"note-",sort=FileSort.MODIFIED)) as FileResult.Page).value.entries.size)
        assertEquals(16,(query(FileQuery.Search(rootId,"note-",sort=FileSort.SIZE)) as FileResult.Page).value.entries.size)
        assertTrue(search("absent-fixture-name").entries.isEmpty())
    }
    @Test fun realDetailsAndMetadataStrippedBoundedPreview()=runBlocking<Unit> {
        val image=search("photo.jpg").entries.single()
        assertEquals(image,(query(FileQuery.Details(rootId,image.object_id)) as FileResult.Details).value)
        val preview=(query(FileQuery.Preview(rootId,image.object_id,image.version)) as FileResult.Preview).value
        assertEquals(20,preview.width);assertEquals(14,preview.height);assertTrue(preview.encodedCopy().size<=32768)
        JpegBounds.validate(preview.encodedCopy(),20,14)
    }
    @Test fun activeFormatAndChangedVersionAreRefusedByActualDesktop()=runBlocking<Unit> {
        val svg=search("active.svg").entries.single()
        expectFailure("PREVIEW_UNSUPPORTED"){runBlocking{query(FileQuery.Preview(rootId,svg.object_id,svg.version))}}
        val image=search("photo.jpg").entries.single();File(share,"photo.jpg").writeBytes(FileSamples.jpeg(21,15))
        expectFailure("STALE_VERSION"){runBlocking{query(FileQuery.Preview(rootId,image.object_id,image.version))}}
    }
    @Test fun currentGrantWithdrawalRevokesBrowseAndRead()=runBlocking<Unit> {
        val image=search("photo.jpg").entries.single();permissions(listOf("browse"))
        expectFailure("ACCESS_DENIED"){runBlocking{query(FileQuery.Preview(rootId,image.object_id,image.version))}}
        permissions(emptyList());assertTrue((query(FileQuery.Roots) as FileResult.Roots).value.roots.isEmpty())
        expectFailure("ACCESS_DENIED"){runBlocking{query(FileQuery.Browse(rootId))}}
    }
    @Test fun grantChangeBetweenFileReplyAndPostCheckDropsData()=runBlocking<Unit> {
        engine.withActiveSession(peer.id){p,s ->
            val changed=object:PeerSession by s {
                override fun request(path:String,body:ByteArray):ByteArray {
                    val result=s.request(path,body);if(path==FileRules.PATH)permissions(listOf("browse"));return result
                }
            }
            expectFailure{runBlocking{client.query(p,changed,FileQuery.Roots)}}
        }
        // Rust closes the old session on grant change; no post-check is claimed on that closed lease.
        // A fresh, pinned lease observes and persists the new grants.
        query(FileQuery.Roots)
        assertFalse("read" in store.document.peer(peer.id).remotePermissions.roots.single().capabilities)
    }
    @Test fun staleCursorAfterReindexCannotContinueTheOldSnapshot()=runBlocking<Unit> {
        val page=(query(FileQuery.Browse(rootId)) as FileResult.Page).value
        File(share,"new-file.txt").writeText("new indexed fixture");reindex()
        expectFailure("CURSOR_STALE"){runBlocking{query(FileQuery.Browse(rootId,cursor=page.next_cursor))}}
    }
    @Test fun renameTrashAndRestoreHaveDurableMatchingReceiptsAndRealEffects()=runBlocking<Unit> {
        val old=search("note-00.txt").entries.single();val renamed=mutate(old,FileAction("rename","renamed.txt"))
        assertEquals(FileOperationState.COMMITTED,renamed.state);assertTrue(File(share,"renamed.txt").isFile);assertFalse(File(share,"note-00.txt").exists())
        val current=search("renamed.txt").entries.single();val trashed=mutate(current,FileAction("trash"))
        assertEquals(FileOperationState.COMMITTED,trashed.state);assertFalse(File(share,"renamed.txt").exists())
        val trash=(query(FileQuery.Trash(rootId)) as FileResult.Trash).value.entries.single()
        assertEquals(trashed.request.operationId,trash.object_id)
        val restored=engine.withActiveSession(peer.id){p,s->client.mutate(p,s,rootId,trash.object_id,trash.version,FileAction("restore"),true)}
        assertEquals(FileOperationState.COMMITTED,restored.state);assertTrue(File(share,"renamed.txt").isFile)
        assertTrue((query(FileQuery.Trash(rootId)) as FileResult.Trash).value.entries.isEmpty())
        assertEquals(3,store.document.fileOperations.count{it.state==FileOperationState.COMMITTED})
    }
    @Test fun lostMutationReplyRecoversSameReceiptAcrossAgentAndPhoneRepositoryReload()=runBlocking<Unit> {
        val entry=search("note-01.txt").entries.single()
        engine.withActiveSession(peer.id){p,s ->
            val lossy=object:PeerSession by s {
                override fun request(path:String,body:ByteArray):ByteArray { val data=s.request(path,body);if(path==FileRules.PATH)fail("PEER_TIMEOUT");return data }
            }
            expectFailure("PEER_TIMEOUT"){runBlocking{client.mutate(p,lossy,rootId,entry.object_id,entry.version,FileAction("rename","recovered.txt"),true)}}
        }
        val saved=store.document.fileOperations.single();assertEquals(FileOperationState.UNCONFIRMED,saved.state);assertTrue(File(share,"recovered.txt").isFile)
        host.restart();engine.setEndpoint(peer.id,host.snapshot()["endpoint"]!!.jsonPrimitive.content)
        store=MemoryTrust(store.key,Wire.decode<TrustDocument>(Wire.encode(store.document,Wire.MAX_STORE),Wire.MAX_STORE))
        engine=PairingEngine(store,connector);client=FilesClient(store,engine)
        val receipt=engine.withActiveSession(peer.id){p,s->client.retry(p,s,saved.request.operationId,true)}
        assertEquals(FileOperationState.COMMITTED,receipt.state);assertEquals(saved.request,receipt.request);assertEquals(1,store.document.fileOperations.size)
        assertEquals("recovered.txt",search("recovered.txt").entries.single().name)
    }
    @Test fun collisionDoesNotOverwriteAndUnknownRecordIsNotReportedAsSuccess()=runBlocking<Unit> {
        val original=File(share,"note-02.txt").readText();val entry=search("note-01.txt").entries.single()
        expectFailure("NAME_COLLISION"){runBlocking{mutate(entry,FileAction("rename","note-02.txt"))}}
        assertEquals(original,File(share,"note-02.txt").readText());assertTrue(File(share,"note-01.txt").exists())
        assertEquals(FileOperationState.UNCONFIRMED,store.document.fileOperations.single().state)
    }
    @Test fun localRevocationStopsFileRequestsAndKeepsTheTrustTombstone()=runBlocking<Unit> {
        query(FileQuery.Roots);engine.revoke(peer.id)
        expectFailure("DEVICE_REVOKED"){runBlocking{query(FileQuery.Browse(rootId))}}
        assertEquals(TrustStatus.REVOKED,store.document.peer(peer.id).status)
    }
    @Test fun actualDesktopRevocationFailsFreshMutualTlsFileLease()=runBlocking<Unit> {
        query(FileQuery.Roots)
        val revision=host.snapshot()["peers"]!!.jsonArray.single().jsonObject["grant_revision"]!!.jsonPrimitive.int
        host.call("revoke_peer",buildJsonObject{put("peer_id",store.key.deviceId);put("expected_revision",revision)})
        expectFailure{runBlocking{query(FileQuery.Roots)}}
    }
}
