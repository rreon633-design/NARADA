package com.narada.connection

import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DownloadAdmissionTest {
    @Test fun exactQueuedSourceReusesOneDurableIntent()=runBlocking<Unit>{val f=DownloadFixture();val first=f.enqueue();val next=f.enqueue();assertEquals(first.id,next.id);assertEquals(1,f.memory.document.downloads.size);assertEquals(0,f.prepareCount)}
    @Test fun simultaneousAdmissionReusesOneRecord()=runBlocking<Unit>{val f=DownloadFixture();val ids=coroutineScope{List(8){async{f.enqueue().id}}.awaitAll()};assertEquals(1,ids.toSet().size);assertEquals(1,f.memory.document.downloads.size)}
    @Test fun pausedExactSourceIsNotResumedByAnotherSelection()=runBlocking<Unit>{val f=DownloadFixture();val first=f.enqueue();f.engine.pause(first.id,"USER_PAUSED");val next=f.enqueue();assertEquals(first.id,next.id);assertEquals(DownloadState.PAUSED,next.state);assertEquals("USER_PAUSED",next.error)}
    @Test fun verifiedCopyReusesItsReceiptWithoutAnotherPrepare()=runBlocking<Unit>{val f=DownloadFixture();val first=f.completed();val next=f.enqueue();assertEquals(first.id,next.id);assertEquals(first.receipt,next.receipt);assertEquals(1,f.prepareCount)}
    @Test fun fullQueueStillAllowsExactReuse()=runBlocking<Unit>{val f=DownloadFixture();val first=f.enqueue();repeat(3){f.engine.enqueue(f.peer.id,f.peer.attemptId,TransferSamples.source.copy(object_id=UUID.randomUUID().toString()),"ordinary.bin",f.bytes.size.toLong(),true)};assertEquals(first.id,f.enqueue().id);assertEquals(4,f.memory.document.downloads.size)}
    @Test fun mismatchUnderSameIdentityIsNotAnotherVersionOrAReusableCopy()=runBlocking<Unit>{val f=DownloadFixture();f.enqueue();expectFailure("MANIFEST_MISMATCH"){runBlocking{f.enqueue(size=1)}};expectFailure("MANIFEST_MISMATCH"){runBlocking{f.engine.enqueue(f.peer.id,f.peer.attemptId,TransferSamples.source,"different.bin",f.bytes.size.toLong(),true)}};assertEquals(1,f.memory.document.downloads.size)}
    @Test fun newVersionAndOtherObjectRemainSeparate()=runBlocking<Unit>{val f=DownloadFixture();val first=f.enqueue();val version=f.engine.enqueue(f.peer.id,f.peer.attemptId,TransferSamples.source.copy(expected_version="b".repeat(64)),"ordinary.bin",f.bytes.size.toLong(),true);val objectCopy=f.engine.enqueue(f.peer.id,f.peer.attemptId,TransferSamples.source.copy(object_id=UUID.randomUUID().toString()),"ordinary.bin",f.bytes.size.toLong(),true);assertEquals(3,setOf(first.id,version.id,objectCopy.id).size)}
    @Test fun cancelledHistoryIsPreservedNotReusedOrDeleted()=runBlocking<Unit>{val f=DownloadFixture();val first=f.enqueue();f.engine.cancel(first.id,true);val next=f.enqueue();assertNotEquals(first.id,next.id);assertEquals(DownloadState.CANCELLED,f.record(first.id).state);assertEquals(2,f.memory.document.downloads.size)}
    @Test fun corruptRecordIsNotAdoptedAsHealthy()=runBlocking<Unit>{val f=DownloadFixture();val first=f.completed();f.memory.document=f.memory.document.replaceDownload(first.copy(state=DownloadState.CORRUPT,receipt=null,error="LOCAL_COPY_CHANGED"));val next=f.enqueue();assertNotEquals(first.id,next.id);assertEquals(DownloadState.CORRUPT,f.record(first.id).state)}
    @Test fun syncScopedCandidateDoesNotBecomeAnOrdinaryDownload()=runBlocking<Unit>{val f=DownloadFixture();val first=f.enqueue();f.memory.document=f.memory.document.replaceDownload(first.copy(syncRequired=true));val next=f.enqueue();assertNotEquals(first.id,next.id);assertTrue(f.record(first.id).syncRequired)}
    @Test fun duplicateStillRequiresExplicitConsent()=runBlocking<Unit>{val f=DownloadFixture();f.enqueue();expectFailure("LOCAL_CONFIRMATION_REQUIRED"){runBlocking{f.enqueue(confirmed=false)}};assertEquals(1,f.memory.document.downloads.size)}
    @Test fun duplicateCannotBypassCurrentGrant()=runBlocking<Unit>{val f=DownloadFixture();f.enqueue();f.memory.document=f.memory.document.replace(f.memory.document.peer(f.peer.id).copy(remotePermissions=Permissions()));expectFailure("ACCESS_DENIED"){runBlocking{f.enqueue()}};assertEquals(1,f.memory.document.downloads.size)}
    @Test fun replacedPairingCannotReuseOriginalRecord()=runBlocking<Unit>{val f=DownloadFixture();f.enqueue();f.memory.document=f.memory.document.replace(f.memory.document.peer(f.peer.id).copy(attemptId=UUID.randomUUID().toString()));expectFailure("TRANSFER_SCOPE_CHANGED"){runBlocking{f.enqueue()}};assertEquals(1,f.memory.document.downloads.size)}
}

class TransferRetryPolicyTest {
    @Test fun onlyShortTransientNetworkFailuresGetTwoBoundedDelays(){listOf("PEER_UNREACHABLE","PEER_TIMEOUT","CONNECTION_INTERRUPTED","RATE_LIMITED").forEach{assertEquals(1500L,TransferRetryPolicy.delayAfter(it,1));assertEquals(3000L,TransferRetryPolicy.delayAfter(it,2));assertNull(TransferRetryPolicy.delayAfter(it,3));assertNull(TransferRetryPolicy.delayAfter(it,0));assertNull(TransferRetryPolicy.delayAfter(it,-1))}}
    @Test fun securityStorageSourceProviderAndLifecycleFailuresAreNeverRetryable(){listOf("IDENTITY_REJECTED","PEER_AUTH_UNCONFIRMED","DEVICE_REVOKED","TRANSFER_ACCESS_CHANGED","SOURCE_CHANGED","MANIFEST_MISMATCH","HASH_MISMATCH","LOCAL_COPY_CHANGED","LOCAL_STORAGE_FULL","STORAGE_UNAVAILABLE","PROVIDER_TIMEOUT","EXPORT_INTERRUPTED","FOREGROUND_PAUSED","USER_PAUSED","RESTART_PAUSED","NETWORK_DISABLED","UNKNOWN").forEach{assertNull(it,TransferRetryPolicy.delayAfter(it,1))}}
}
