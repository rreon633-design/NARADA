package com.narada.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PacedTransfersGatewayTest {
    private class Gateway(private val now:()->Long):TransfersGateway {
        val starts=mutableListOf<Long>();var cancels=0;var reject=false
        override suspend fun prepare(record:DownloadRecord):ManifestBundle{starts+=now();if(reject)fail("IDENTITY_REJECTED");return TransferSamples.bundle(byteArrayOf())}
        override suspend fun chunk(record:DownloadRecord,index:Int):ByteArray{starts+=now();return byteArrayOf()}
        override suspend fun finish(record:DownloadRecord){starts+=now()}
        override fun cancel(){cancels++}
    }
    @Test fun allTransferLeasesIncludingFinishShareOneAdmissionClock()=runTest {
        val raw=Gateway{testScheduler.currentTime};val g=PacedTransfersGateway(raw){testScheduler.currentTime};val r=TransferPeerFixture().record
        g.prepare(r);g.chunk(r,0);g.chunk(r,1);g.finish(r)
        assertEquals(listOf(0L,300L,600L,900L),raw.starts)
    }
    @Test fun cancelledPacingDoesNotAdmitTheNextNetworkRequest()=runTest {
        val raw=Gateway{testScheduler.currentTime};val g=PacedTransfersGateway(raw){testScheduler.currentTime};val r=TransferPeerFixture().record
        g.prepare(r);val waiting=launch{g.chunk(r,0)};runCurrent();waiting.cancelAndJoin();g.cancel()
        assertEquals(listOf(0L),raw.starts);assertEquals(1,raw.cancels)
    }
    @Test fun concurrentCommandsAreSerializedAndNotBurstReplayed()=runTest {
        val raw=Gateway{testScheduler.currentTime};val g=PacedTransfersGateway(raw){testScheduler.currentTime};val r=TransferPeerFixture().record
        List(4){async{g.chunk(r,it)}}.awaitAll();assertEquals(listOf(0L,300L,600L,900L),raw.starts)
    }
    @Test fun identityFailureIsNotAutomaticallyRetriedOrDowngraded()=runTest {
        val raw=Gateway{testScheduler.currentTime}.apply{reject=true};val g=PacedTransfersGateway(raw){testScheduler.currentTime};val r=TransferPeerFixture().record
        try{g.prepare(r);org.junit.Assert.fail("Expected failure")}catch(e:ConnectionFailure){assertEquals("IDENTITY_REJECTED",e.code)}
        advanceUntilIdle();assertEquals(listOf(0L),raw.starts)
    }
}
