package com.narada.android

import com.narada.android.connection.*
import com.narada.android.files.RemoteFilesGateway
import com.narada.connection.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class FileLeaseCoordinatorTest {
    private lateinit var foundation:TestFixture
    private lateinit var scope:CoroutineScope
    private lateinit var repository:ProtectedConnectionsRepository
    private lateinit var blob:TestBlob
    private lateinit var network:ConnectionsCoordinatorTest.NetworkPolicy
    private lateinit var coordinator:ConnectionsCoordinator
    private lateinit var gateway:RemoteFilesGateway
    private lateinit var peer:TrustedPeer
    private val root=UUID.randomUUID().toString()
    private val sessions=ConcurrentHashMap.newKeySet<Session>()
    private val maximum=AtomicInteger();private val bodies=AtomicInteger()
    private val entered=CountDownLatch(1);private val release=CountDownLatch(1)
    @Volatile private var hold=false
    @Volatile private var remoteError:String?=null
    private val grants=Permissions(roots=listOf(RootGrant(root,listOf("browse","read","modify","trash","create"))))
    private inner class Session(override val certificate:ByteArray):PeerSession {
        @Volatile var closed=false
        @Volatile var waiting=false
        init { sessions.add(this);maximum.updateAndGet{maxOf(it,sessions.size)} }
        override fun close(){closed=true;sessions.remove(this);if(waiting)release.countDown()}
        override fun request(path:String,body:ByteArray):ByteArray {
            demand(!closed,"CONNECTION_INTERRUPTED")
            if(path=="/api/v1/health") return Wire.encode(Envelope(true,Health(Hello(),peer.id,grants,1,1,listOf("health","permissions","files.v1")),null))
            check(path==FileRules.PATH);bodies.incrementAndGet()
            if(hold){waiting=true;entered.countDown();check(release.await(5,TimeUnit.SECONDS));demand(!closed,"CONNECTION_INTERRUPTED")}
            remoteError?.let{return Wire.encode(Envelope<JsonElement>(false,null,RemoteError(it,"private remote error")))}
            return Wire.encode(Envelope(true,RootsReply(emptyList(),1),null))
        }
    }
    @Before fun setup()=runBlocking<Unit> {
        foundation=TestFixture();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);blob=TestBlob();repository=ProtectedConnectionsRepository(TestAnchor(),TestKeys(),blob);repository.initialize()
        val host=AndroidTestIdentities.create(true)
        peer=TrustedPeer(host.deviceId,host.fingerprint,Base64.getEncoder().encodeToString(host.certificate.encoded),"File lease fixture","linux","192.168.1.5:12345",UUID.randomUUID().toString(),UUID.randomUUID().toString(),System.currentTimeMillis()+60000,TrustStatus.ACTIVE)
        repository.mutate{it.copy(enabled=true,preferredPeer=peer.id,peers=listOf(peer))}
        network=ConnectionsCoordinatorTest.NetworkPolicy()
        val connector=object:PeerConnector {
            override fun connect(endpoint:Endpoint,pin:PeerPin,identity:TransportIdentity?):PeerSession {check(identity!=null);return Session(host.certificate.encoded)}
            override fun closeAll(){sessions.toList().forEach{it.close()}}
        }
        coordinator=ConnectionsCoordinator(repository,connector,network,ConnectionsCoordinatorTest.Discovery(),foundation.settings,scope);gateway=RemoteFilesGateway(repository,coordinator)
    }
    private suspend fun ready(){coordinator.foregroundChanged(true);withTimeout(5000){coordinator.state.first{it.connectedPeer==peer.id}}}
    @After fun cleanup()=runBlocking<Unit> {release.countDown();if(::coordinator.isInitialized)coordinator.foregroundChanged(false);if(::scope.isInitialized)scope.coroutineContext[Job]?.cancelAndJoin();if(::foundation.isInitialized)foundation.close()}
    @Test fun fileWorkBorrowsTheOwnerAndNeverOverlapsHealthSockets()=runBlocking<Unit> {
        ready();assertTrue(gateway.query(FileQuery.Roots) is FileResult.Roots)
        withTimeout(5000){coordinator.state.first{it.connectedPeer==peer.id&&!it.busy}}
        assertEquals(1,maximum.get());assertEquals(1,bodies.get());assertTrue(coordinator.state.value.transportAllowed)
    }
    @Test fun concurrentUiQueriesRemainSerializedThroughTheSameOwner()=runBlocking<Unit> {
        ready();coroutineScope{List(4){async(Dispatchers.Default){gateway.query(FileQuery.Roots)}}.awaitAll()}
        assertEquals(1,maximum.get());assertEquals(4,bodies.get())
    }
    @Test fun disabledOrBackgroundPolicyStopsNewFileLeases()=runBlocking<Unit> {
        ready();coordinator.foregroundChanged(false)
        try{gateway.query(FileQuery.Roots);org.junit.Assert.fail("Expected denial")}catch(f:ConnectionFailure){assertEquals("NETWORK_DISABLED",f.code)}
        assertEquals(0,bodies.get())
    }
    @Test fun backgroundClosesAnInFlightLeaseAndFencesItsReply()=runBlocking<Unit> {
        ready();hold=true
        val result=async(Dispatchers.Default){runCatching{gateway.query(FileQuery.Roots)}}
        assertTrue(entered.await(5,TimeUnit.SECONDS));coordinator.foregroundChanged(false)
        assertTrue(result.await().isFailure);assertNull(coordinator.state.value.connectedPeer);assertFalse(network.foreground)
    }
    @Test fun routeChangesIncrementTheFileAuthorityFence()=runBlocking<Unit> {
        ready();val before=coordinator.state.value.accessEpoch;network.eligible=false;network.onRouteChanged()
        assertTrue(coordinator.state.value.accessEpoch>before);assertFalse(coordinator.state.value.networkAvailable)
        try{gateway.query(FileQuery.Roots);org.junit.Assert.fail("Expected denial")}catch(f:ConnectionFailure){assertEquals("LOCAL_NETWORK_UNAVAILABLE",f.code)}
    }
    @Test fun localIntentWriteFailureLatchesNetworkingAndSendsNoMutation()=runBlocking<Unit> {
        ready();blob.failWrite=true
        try{gateway.mutate(root,UUID.randomUUID().toString(),"a".repeat(64),FileAction("rename","changed.txt"),true);org.junit.Assert.fail("Expected storage error")}catch(f:ConnectionFailure){assertEquals("STORAGE_UNAVAILABLE",f.code)}
        assertEquals(0,bodies.get());assertFalse(network.allowed);assertFalse(coordinator.state.value.transportAllowed);assertTrue(repository.read()!!.fileOperations.isEmpty())
    }
    @Test fun desktopStorageErrorDoesNotLatchThePhonesProtectedStorage()=runBlocking<Unit> {
        ready();remoteError="STORAGE_UNAVAILABLE"
        try{gateway.query(FileQuery.Roots);org.junit.Assert.fail("Expected remote error")}catch(f:ConnectionFailure){assertEquals("FILE_REMOTE_STORAGE",f.code)}
        assertTrue(network.allowed);assertEquals(1,bodies.get())
    }
    @Test fun leavingFilesCannotCancelTheTransferOwnedLease()=runBlocking<Unit> {
        ready();hold=true
        val result=async(Dispatchers.Default){runCatching{coordinator.withTransfer(peer.id){_,_,session,guard->guard();session.request(FileRules.PATH,byteArrayOf());guard()}}}
        assertTrue(entered.await(5,TimeUnit.SECONDS));gateway.cancel();assertEquals(1L,release.count)
        release.countDown();assertTrue(result.await().isSuccess);assertEquals(1,maximum.get())
    }
    @Test fun transferCancellationCannotCloseTheFilesOwnedLease()=runBlocking<Unit> {
        ready();hold=true;val result=async(Dispatchers.Default){runCatching{gateway.query(FileQuery.Roots)}}
        assertTrue(entered.await(5,TimeUnit.SECONDS));coordinator.cancelTransfers();assertEquals(1L,release.count)
        release.countDown();assertTrue(result.await().isSuccess);assertEquals(1,maximum.get())
    }
    @Test fun transferOwnerCanCancelOnlyItsCurrentRequest()=runBlocking<Unit> {
        ready();hold=true
        val result=async(Dispatchers.Default){runCatching{coordinator.withTransfer(peer.id){_,_,session,guard->guard();session.request(FileRules.PATH,byteArrayOf());guard()}}}
        assertTrue(entered.await(5,TimeUnit.SECONDS));coordinator.cancelTransfers();assertTrue(result.await().isFailure)
        assertEquals(1,maximum.get())
    }

}
