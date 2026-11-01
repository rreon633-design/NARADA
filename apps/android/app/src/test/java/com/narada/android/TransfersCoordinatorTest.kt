package com.narada.android

import com.narada.android.connection.*
import com.narada.android.transfers.*
import com.narada.connection.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class TransfersCoordinatorTest {
    private lateinit var foundation:TestFixture;private lateinit var scope:CoroutineScope
    private lateinit var repo:ProtectedConnectionsRepository;private lateinit var blob:TestBlob
    private lateinit var peer:TrustedPeer;private lateinit var connections:ConnectionsCoordinator;private lateinit var coordinator:TransfersCoordinator
    private val storage=TestTransferChunks();private val prepares=AtomicInteger();private val providerFactories=AtomicInteger()
    private var beforeChunk:suspend (Int)->Unit={};private var failProvider=false
    @Volatile private var heldProvider=false
    private val enteredProvider=CountDownLatch(1);private val releaseProvider=CountDownLatch(1)
    private val sourceCreates=AtomicInteger();private val exported=ByteArrayOutputStream()
    private val gateway=object:TransfersGateway {
        override suspend fun prepare(record:DownloadRecord):ManifestBundle{assertEquals(DownloadState.RUNNING,repo.read()!!.download(record.id).state);prepares.incrementAndGet();return TransferTestData.bundle(record.name)}
        override suspend fun chunk(record:DownloadRecord,index:Int):ByteArray {beforeChunk(index);return TransferTestData.bytes.copyOfRange(index*TransferRules.CHUNK,minOf((index+1)*TransferRules.CHUNK,TransferTestData.bytes.size))}
        override suspend fun finish(record:DownloadRecord){demand(connections.state.value.transportAllowed,"NETWORK_DISABLED");assertNotNull(repo.read()!!.download(record.id).receipt)}
        override fun cancel(){}
    }
    @Before fun setup()=runBlocking<Unit>{
        foundation=TestFixture();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);blob=TestBlob();repo=ProtectedConnectionsRepository(TestAnchor(),TestKeys(),blob);repo.initialize();peer=TransferTestData.peer();repo.mutate{it.copy(enabled=true,preferredPeer=peer.id,peers=listOf(peer))}
        val connector=object:PeerConnector{override fun closeAll(){};override fun connect(endpoint:Endpoint,pin:PeerPin,identity:TransportIdentity?)=object:PeerSession{override val certificate=decodeCertificate(peer.certificate);override fun close(){};override fun request(path:String,body:ByteArray):ByteArray{check(path=="/api/v1/health");return Wire.encode(Envelope(true,Health(Hello(),peer.id,peer.remotePermissions,1,1,peer.remoteOperations),null))}}}
        connections=ConnectionsCoordinator(repo,connector,ConnectionsCoordinatorTest.NetworkPolicy(),ConnectionsCoordinatorTest.Discovery(),foundation.settings,scope)
        val factory=ExportProviderFactory{_,_->providerFactories.incrementAndGet();if(failProvider)throw ConnectionFailure("PROVIDER_PERMISSION_LOST");object:NewDocumentProvider{
            override fun preflight(tree:String,name:String){}
            override fun create(tree:String,name:String):String{sourceCreates.incrementAndGet();return "content://fixture.documents/document/new"}
            override fun writer(document:String):java.io.OutputStream {if(heldProvider){enteredProvider.countDown();while(releaseProvider.count>0)try{releaseProvider.await()}catch(_:InterruptedException){}};return exported}
            override fun reader(document:String)=ByteArrayInputStream(exported.toByteArray())
            override fun cancel(){}
            override fun close(){}
        }}
        coordinator=TransfersCoordinator(repo,gateway,storage,connections,factory,BoundedProviderIo(3000),scope)
        connections.foregroundChanged(true);withTimeout(5000){connections.state.first{it.connectedPeer==peer.id}};await{!it.loading&&it.foreground&&it.networkEligible}
    }
    @After fun cleanup()=runBlocking<Unit>{releaseProvider.countDown();if(::connections.isInitialized)connections.foregroundChanged(false);if(::scope.isInitialized)withTimeout(10000){scope.coroutineContext[Job]?.cancelAndJoin()};if(::foundation.isInitialized)foundation.close()}
    private suspend fun await(predicate:(TransfersUiState)->Boolean)=withTimeout(10000){coordinator.state.first(predicate)}
    private suspend fun saved():DownloadRecord{coordinator.enqueue(TransferTestData.choice(peer),true);return await{it.records.size==1&&it.records.single().state==DownloadState.VERIFIED&&it.activeId==null}.records.single()}
    private suspend fun picker(r:DownloadRecord):ExportPicker{coordinator.prepareExport(r.id,"copy.bin",true);return await{it.picker!=null}.picker!!.also{assertTrue(coordinator.claimPicker(it.exportId))}}
    @Test fun consentQueuesRealEngineProgressAndDurablePrivateReceipt()=runBlocking<Unit>{coordinator.enqueue(TransferTestData.choice(peer),false);await{it.error=="LOCAL_CONFIRMATION_REQUIRED"};assertEquals(0,prepares.get());assertTrue(repo.read()!!.downloads.isEmpty());val r=saved();assertEquals(TransferTestData.bytes.size.toLong(),r.verifiedBytes);assertEquals(1,prepares.get());assertTrue(r.remoteFinished);assertEquals(3,storage.data.size)}
    @Test fun protectedIntentWriteFailureBlocksBeforeAnyGatewayUse()=runBlocking<Unit>{blob.failWrite=true;coordinator.enqueue(TransferTestData.choice(peer),true);await{it.error=="STORAGE_UNAVAILABLE"};assertEquals(0,prepares.get());assertFalse(connections.state.value.transportAllowed);assertTrue(repo.read()!!.downloads.isEmpty())}
    @Test fun pauseJoinsLateWorkBeforeKeepingItsDurableCheckpoint()=runBlocking<Unit>{val entered=CompletableDeferred<Unit>();beforeChunk={if(it==1){entered.complete(Unit);withContext(NonCancellable){delay(250)}}};coordinator.enqueue(TransferTestData.choice(peer),true);withTimeout(5000){entered.await()};val id=repo.read()!!.downloads.single().id;coordinator.pause(id);val r=await{it.records.singleOrNull()?.state==DownloadState.PAUSED&&it.activeId==null}.records.single();assertEquals(listOf(0),r.chunks);assertFalse(storage.data.containsKey(id to 1));assertNull(r.receipt)}
    @Test fun returningForegroundDoesNotAutomaticallyResumePausedDownload()=runBlocking<Unit>{val entered=CompletableDeferred<Unit>();beforeChunk={if(it==1){entered.complete(Unit);delay(30000)}};coordinator.enqueue(TransferTestData.choice(peer),true);withTimeout(5000){entered.await()};connections.foregroundChanged(false);val paused=await{it.records.singleOrNull()?.state==DownloadState.PAUSED&&!it.foreground}.records.single();beforeChunk={};connections.foregroundChanged(true);await{it.foreground&&it.networkEligible};delay(100);assertEquals(DownloadState.PAUSED,repo.read()!!.download(paused.id).state);coordinator.resume(paused.id,true);assertEquals(paused.id,await{it.records.single().state==DownloadState.VERIFIED}.records.single().id)}
    @Test fun fullyStagedRestartCanBeExplicitlyVerifiedWithoutNewRemoteAccess()=runBlocking<Unit>{val full=TransferTestData.verified(peer);val r=full.copy(state=DownloadState.RUNNING,runId=UUID.randomUUID().toString(),receipt=null);storage.seed(r);repo.mutate{it.copy(enabled=false,downloads=listOf(r))};connections.foregroundChanged(true);await{!it.networkEligible&&it.foreground};coordinator.reload();await{it.records.singleOrNull()?.state==DownloadState.PAUSED};assertEquals(0,prepares.get());coordinator.resume(r.id,true);val done=await{it.records.single().state==DownloadState.VERIFIED&&it.activeId==null}.records.single();assertNotNull(done.receipt);assertFalse(done.remoteFinished);assertEquals(0,prepares.get())}
    @Test fun pickerClaimAndMatchingResultAreSingleUse()=runBlocking<Unit>{val r=saved();val ticket=picker(r);assertFalse(coordinator.claimPicker(ticket.exportId));coordinator.pickerResult(UUID.randomUUID().toString(),"content://fixture.documents/tree/root",3);delay(100);assertEquals(ticket,coordinator.state.value.picker);assertEquals(0,providerFactories.get());coordinator.pickerResult(ticket.exportId,"content://fixture.documents/tree/root",3);await{it.records.single().exports.lastOrNull()?.state==ExportState.VERIFIED&&!it.providerBusy};coordinator.pickerResult(ticket.exportId,"content://fixture.documents/tree/root",3);delay(100);assertEquals(1,sourceCreates.get());assertArrayEquals(TransferTestData.bytes,exported.toByteArray())}
    @Test fun providerFactoryRefusalSettlesPickerWithoutStrandingAnIntent()=runBlocking<Unit>{val r=saved();failProvider=true;val ticket=picker(r);coordinator.pickerResult(ticket.exportId,"content://fixture.documents/tree/root",1);await{it.records.single().exports.lastOrNull()?.state==ExportState.NOT_CREATED};assertNull(coordinator.state.value.picker);assertEquals(0,sourceCreates.get());assertEquals(DownloadState.VERIFIED,repo.read()!!.download(r.id).state)}
    @Test fun reloadInvalidatesOldPickerAndCannotRebindItsLateResult()=runBlocking<Unit>{val r=saved();val old=picker(r);coordinator.reload();await{it.picker==null&&it.records.single().exports.last().state==ExportState.NOT_CREATED};val next=picker(r);assertNotEquals(old.exportId,next.exportId);coordinator.pickerResult(old.exportId,"content://fixture.documents/tree/root",3);delay(100);assertEquals(next,coordinator.state.value.picker);assertEquals(0,sourceCreates.get());coordinator.pickerResult(next.exportId,null,0);await{it.picker==null&&it.records.single().exports.last().state==ExportState.NOT_CREATED}}
    @Test fun timedOutProviderCannotPublishLateReceiptOrStartAnotherExport()=runBlocking<Unit>{val r=saved();heldProvider=true;val ticket=picker(r);coordinator.pickerResult(ticket.exportId,"content://fixture.documents/tree/root",3);assertTrue(enteredProvider.await(5,TimeUnit.SECONDS));val stopped=await{it.error=="PROVIDER_TIMEOUT"&&it.records.single().exports.last().state==ExportState.UNCERTAIN};assertTrue(stopped.providerBusy);coordinator.prepareExport(r.id,"another.bin",true);await{it.error=="PROVIDER_BUSY"};assertEquals(1,sourceCreates.get());releaseProvider.countDown();await{!it.providerBusy};assertEquals(ExportState.UNCERTAIN,repo.read()!!.download(r.id).exports.last().state);assertEquals(0,exported.size());assertEquals(DownloadState.VERIFIED,repo.read()!!.download(r.id).state)}
}
