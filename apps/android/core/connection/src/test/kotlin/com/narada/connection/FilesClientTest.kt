package com.narada.connection

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FilesClientTest {
    @Test fun readsRecheckHealthBeforeAndAfterTheFileResponse() = runBlocking<Unit> {
        val f=FilePeerFixture();assertTrue(f.query() is FileResult.Roots);assertEquals(2,f.healthCount);assertEquals(1,f.fileCount)
        assertTrue("files.v1" in f.currentPeer().remoteOperations)
    }
    @Test fun deniedRootNeverSendsAFileBody() = runBlocking<Unit> {
        val f=FilePeerFixture();f.health=f.health.copy(permissions=Permissions())
        expectFailure("ACCESS_DENIED") { runBlocking { f.query(FileQuery.Browse(FileSamples.rootId)) } };assertEquals(0,f.fileCount)
    }
    @Test fun previewNeedsReadAndTrashNeedsItsOwnCapability() = runBlocking<Unit> {
        val f=FilePeerFixture();f.health=f.health.copy(permissions=Permissions(roots=listOf(RootGrant(FileSamples.rootId,listOf("browse")))))
        expectFailure("ACCESS_DENIED") { runBlocking { f.query(FileQuery.Preview(FileSamples.rootId,FileSamples.objectId,FileSamples.version)) } }
        expectFailure("ACCESS_DENIED") { runBlocking { f.query(FileQuery.Trash(FileSamples.rootId)) } };assertEquals(0,f.fileCount)
    }
    @Test fun absentAdvertisedFileProtocolIsNotGuessedFromGrants() = runBlocking<Unit> {
        val f=FilePeerFixture();f.health=f.health.copy(operations=listOf("health","permissions"))
        expectFailure("FILES_UNSUPPORTED") { runBlocking { f.query() } };assertEquals(0,f.fileCount)
    }
    @Test fun hostileIdsAreRejectedEvenBeforeHealth() = runBlocking<Unit> {
        val f=FilePeerFixture();expectFailure("INVALID_FILE_ID") { runBlocking { f.query(FileQuery.Details("../","content://x")) } };assertEquals(0,f.fileCount);assertEquals(0,f.healthCount)
    }
    @Test fun changedGrantsAfterReadingDiscardTheResult() = runBlocking<Unit> {
        val f=FilePeerFixture();f.afterFile={f.health=f.health.copy(permissions=Permissions(),grant_revision=2,session_epoch=2)}
        expectFailure("FILE_ACCESS_CHANGED") { runBlocking { f.query() } };assertEquals(Permissions(),f.currentPeer().remotePermissions)
    }
    @Test fun localRevocationDuringResponseCannotPublishMetadata() = runBlocking<Unit> {
        val f=FilePeerFixture();f.afterFile={runBlocking { f.engine.revoke(f.peer.id) }}
        expectFailure("PAIRING_STATE_CHANGED") { runBlocking { f.query() } };assertEquals(TrustStatus.REVOKED,f.currentPeer().status)
    }
    @Test fun routeGuardFencesLatePreview() = runBlocking<Unit> {
        val f=FilePeerFixture();var route=true;f.afterFile={route=false}
        expectFailure("FILE_ACCESS_CHANGED") { runBlocking { f.client.query(f.currentPeer(),f.session,FileQuery.Preview(FileSamples.rootId,FileSamples.objectId,FileSamples.version)){demand(route,"FILE_ACCESS_CHANGED")} } }
    }
    @Test fun selectedDetailsMustBindRequestedObject() = runBlocking<Unit> {
        val f=FilePeerFixture();f.responseTransform={JsonObject(it.jsonObject+mapOf("object_id" to JsonPrimitive(FileSamples.directoryId)))}
        expectFailure("INVALID_FILE_RESPONSE") { runBlocking { f.query(FileQuery.Details(FileSamples.rootId,FileSamples.objectId)) } }
    }
    @Test fun mutationRequiresExplicitConfirmationAndCurrentModifyGrant() = runBlocking<Unit> {
        val f=FilePeerFixture();expectFailure("LOCAL_CONFIRMATION_REQUIRED") { runBlocking { f.mutate(false) } }
        f.health=f.health.copy(permissions=Permissions(roots=listOf(RootGrant(FileSamples.rootId,listOf("browse","read")))))
        expectFailure("ACCESS_DENIED") { runBlocking { f.mutate() } };assertTrue(f.memory.document.fileOperations.isEmpty());assertEquals(0,f.fileCount)
    }
    @Test fun restoreRequiresBothTrashAndCreate() = runBlocking<Unit> {
        val f=FilePeerFixture();f.health=f.health.copy(permissions=Permissions(roots=listOf(RootGrant(FileSamples.rootId,listOf("browse","trash")))))
        expectFailure("ACCESS_DENIED") { runBlocking { f.client.mutate(f.currentPeer(),f.session,FileSamples.rootId,FileSamples.cursor,FileSamples.version,FileAction("restore"),true) } };assertEquals(0,f.fileCount)
    }
    @Test fun durableUnknownIntentPrecedesEffectfulRequest() = runBlocking<Unit> {
        val f=FilePeerFixture();f.beforeFile={payload ->
            val saved=f.memory.document.fileOperations.single();assertEquals(FileOperationState.UNCONFIRMED,saved.state)
            assertEquals(saved.request.operationId,payload["operation_id"]!!.jsonPrimitive.content)
            assertEquals(saved.request.payload(),payload)
        }
        val result=f.mutate();assertEquals(FileOperationState.COMMITTED,result.state);assertEquals(result,f.memory.document.fileOperations.single());assertNotNull(result.receipt)
    }
    @Test fun failedIntentWritePreventsEveryEffectfulPost() = runBlocking<Unit> {
        val f=FilePeerFixture();f.beforeWrite={if(it.fileOperations.isNotEmpty())fail("STORAGE_UNAVAILABLE")}
        expectFailure("STORAGE_UNAVAILABLE") { runBlocking { f.mutate() } };assertEquals(0,f.fileCount);assertTrue(f.memory.document.fileOperations.isEmpty())
    }
    @Test fun failedReceiptWriteNeverReturnsSuccess() = runBlocking<Unit> {
        val f=FilePeerFixture();f.beforeWrite={if(it.fileOperations.any { operation -> operation.state==FileOperationState.COMMITTED })fail("STORAGE_UNAVAILABLE")}
        expectFailure("STORAGE_UNAVAILABLE") { runBlocking { f.mutate() } };assertEquals(1,f.fileCount);assertEquals(FileOperationState.UNCONFIRMED,f.memory.document.fileOperations.single().state)
    }
    @Test fun lostReplySurvivesReloadAndReplaysExactIdentifierAndBody() = runBlocking<Unit> {
        val f=FilePeerFixture();f.afterFile={fail("PEER_TIMEOUT")}
        expectFailure("PEER_TIMEOUT") { runBlocking { f.mutate() } }
        val stored=f.memory.document.fileOperations.single();assertEquals(FileOperationState.UNCONFIRMED,stored.state)
        val reopened=MemoryTrust(f.memory.key,Wire.decode<TrustDocument>(Wire.encode(f.memory.document,Wire.MAX_STORE),Wire.MAX_STORE))
        f.afterFile={}
        val client=FilesClient(reopened,PairingEngine(reopened,f.pairingFixture))
        val recovered=client.retry(reopened.document.peer(f.peer.id),f.session,stored.request.operationId,true)
        assertEquals(FileOperationState.COMMITTED,recovered.state);assertEquals(f.sent[0],f.sent[1]);assertEquals(1,reopened.document.fileOperations.size)
    }
    @Test fun unknownOutcomeBlocksANewOperationOnThatPeer() = runBlocking<Unit> {
        val f=FilePeerFixture();f.afterFile={fail("PEER_TIMEOUT")};expectFailure { runBlocking { f.mutate() } };f.afterFile={}
        expectFailure("FILE_OPERATION_UNRESOLVED") { runBlocking { f.mutate() } };assertEquals(1,f.fileCount)
    }
    @Test fun denialOnRecoveryDoesNotRewriteAPossiblyCommittedOutcomeAsFailure() = runBlocking<Unit> {
        val f=FilePeerFixture();f.afterFile={fail("PEER_TIMEOUT")};expectFailure { runBlocking { f.mutate() } };f.afterFile={}
        f.health=f.health.copy(permissions=Permissions(),grant_revision=2,session_epoch=2)
        val saved=f.memory.document.fileOperations.single()
        expectFailure("ACCESS_DENIED") { runBlocking { f.client.retry(f.currentPeer(),f.session,saved.request.operationId,true) } }
        assertEquals(saved,f.memory.document.fileOperations.single());assertEquals(1,f.fileCount)
    }
    @Test fun replacementPairingCannotReplayOldFileIntent() = runBlocking<Unit> {
        val f=FilePeerFixture();f.afterFile={fail("PEER_TIMEOUT")};expectFailure { runBlocking { f.mutate() } };f.afterFile={}
        val saved=f.memory.document.fileOperations.single();f.memory.document=f.memory.document.replace(f.currentPeer().copy(attemptId=UUID.randomUUID().toString()))
        expectFailure("FILE_OPERATION_SCOPE_CHANGED") { runBlocking { f.client.retry(f.currentPeer(),f.session,saved.request.operationId,true) } };assertEquals(1,f.fileCount)
    }
    @Test fun malformedReceiptLeavesDurableUnknown() = runBlocking<Unit> {
        val f=FilePeerFixture();f.responseTransform={JsonObject(it.jsonObject+mapOf("state" to JsonPrimitive("queued")))}
        expectFailure("INVALID_FILE_RECEIPT") { runBlocking { f.mutate() } };assertEquals(FileOperationState.UNCONFIRMED,f.memory.document.fileOperations.single().state)
    }
    @Test fun serverFileStorageFailureCannotMasqueradeAsPhoneStorageFailure() = runBlocking<Unit> {
        val f=FilePeerFixture();f.error="STORAGE_UNAVAILABLE"
        expectFailure("FILE_REMOTE_STORAGE") { runBlocking { f.query() } }
    }
    @Test fun unknownRemoteErrorAndProseAreNotExposed() = runBlocking<Unit> {
        val f=FilePeerFixture();f.error="PRIVATE_PATH_AND_SECRET"
        expectFailure("PEER_REJECTED") { runBlocking { f.query() } }
    }
    @Test fun dismissalIsExplicitLocalBookkeepingNotSuccessOrCancellation() = runBlocking<Unit> {
        val f=FilePeerFixture();f.afterFile={fail("PEER_TIMEOUT")};expectFailure { runBlocking { f.mutate() } }
        val saved=f.memory.document.fileOperations.single()
        expectFailure("LOCAL_CONFIRMATION_REQUIRED") { runBlocking { f.client.dismiss(saved.request.operationId,false) } }
        f.client.dismiss(saved.request.operationId,true)
        assertEquals(FileOperationState.DISMISSED_UNCONFIRMED,f.memory.document.fileOperations.single().state);assertNull(f.memory.document.fileOperations.single().receipt);assertEquals(1,f.fileCount)
    }
    @Test fun cancellationAfterSendingNeverInventsAReceipt() = runBlocking<Unit> {
        val f=FilePeerFixture();f.afterFile={throw CancellationException("cancel fixture")}
        try { f.mutate();org.junit.Assert.fail("Expected cancellation") } catch (_:CancellationException) {}
        assertEquals(FileOperationState.UNCONFIRMED,f.memory.document.fileOperations.single().state)
    }
    @Test fun terminalReceiptWindowIsBoundedAndOnlyTerminalRecordsEvicted() = runBlocking<Unit> {
        val f=FilePeerFixture();val records=List(32) { val input=FileSamples.input();FileOperation(f.peer.id,f.peer.attemptId,input,1,state=FileOperationState.COMMITTED,receipt=FileSamples.receipt(input)) }
        f.memory.document=f.memory.document.copy(fileOperations=records)
        f.mutate();assertEquals(32,f.memory.document.fileOperations.size);assertFalse(f.memory.document.fileOperations.contains(records.first()));assertTrue(f.memory.document.fileOperations.contains(records.last()))
    }
}
