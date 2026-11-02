package com.narada.android

import com.narada.android.connection.*
import com.narada.connection.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Coordinator-only tests: fake route/discovery and peer, real sealed-repository state machine.
 * Real TLS is covered separately; none of these simulate AndroidKeyStore hardware. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=android.app.Application::class)
class ConnectionsCoordinatorTest {
    class NetworkPolicy : ConnectionNetworkPolicy {
        @Volatile override var foreground=false
        @Volatile override var allowed=false
        override var onRouteChanged:()->Unit={}
        var eligible=true
        override fun available()=eligible
    }
    class Discovery : DiscoveryControl { val stops=AtomicInteger();val starts=AtomicInteger();override fun start(){starts.incrementAndGet()};override fun stop(){stops.incrementAndGet()} }
    private lateinit var foundation:TestFixture
    private lateinit var scope:CoroutineScope
    private lateinit var repository:ProtectedConnectionsRepository
    private lateinit var blob:TestBlob
    private lateinit var keys:TestKeys
    private lateinit var network:NetworkPolicy
    private lateinit var discovery:Discovery
    private lateinit var coordinator:ConnectionsCoordinator
    private lateinit var peer:TrustedPeer
    private lateinit var connector:PeerConnector
    private val proofs=AtomicInteger()
    private val acknowledgements=AtomicInteger()
    @Volatile private var ownerApproved=false
    private val requests=AtomicInteger()
    @Volatile private var invalidHealth=false
    @Before fun setup()=runBlocking<Unit> {
        foundation=TestFixture();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        keys=TestKeys();blob=TestBlob();repository=ProtectedConnectionsRepository(TestAnchor(),keys,blob);repository.initialize()
        val host=AndroidTestIdentities.create(true)
        peer=TrustedPeer(host.deviceId,host.fingerprint,Base64.getEncoder().encodeToString(host.certificate.encoded),"Fixture desktop", "windows","192.168.0.10:12345",UUID.randomUUID().toString(),UUID.randomUUID().toString(),System.currentTimeMillis()+120000,TrustStatus.ACTIVE)
        repository.mutate { it.copy(enabled=true,preferredPeer=peer.id,peers=listOf(peer)) }
        network=NetworkPolicy();discovery=Discovery()
        connector=object:PeerConnector {
            override fun closeAll() {}
            override fun connect(endpoint:Endpoint,pin:PeerPin,identity:TransportIdentity?)=object:PeerSession {
                override val certificate=host.certificate.encoded
                override fun close(){}
                override fun request(path:String,body:ByteArray):ByteArray {
                    requests.incrementAndGet()
                    if(path=="/pair/v1/prove") { proofs.incrementAndGet();return Wire.encode(Envelope(true,PairState(Hello(),if(ownerApproved)"approved" else "awaiting_owner",Permissions()),null)) }
                    if(path=="/pair/v1/ack") { acknowledgements.incrementAndGet();assertTrue(ownerApproved);return Wire.encode(Envelope(true,PairState(Hello(),"active",Permissions()),null)) }
                    assertEquals("/api/v1/health",path)
                    return Wire.encode(Envelope(true,Health(Hello(),if(invalidHealth)"a".repeat(64)else peer.id,Permissions(),1,1,listOf("health","permissions")),null))
                }
            }
        }
        coordinator=ConnectionsCoordinator(repository,connector,network,discovery,foundation.settings,scope)
    }
    @After fun cleanup()=runBlocking<Unit> { if(::coordinator.isInitialized)coordinator.foregroundChanged(false);if(::scope.isInitialized)scope.coroutineContext[Job]?.cancelAndJoin();if(::foundation.isInitialized)foundation.close() }
    private suspend fun await(predicate:(ConnectionsUiState)->Boolean)=withTimeout(5000){coordinator.state.first(predicate)}
    @Test fun savedTrustIsNotOnlineUntilAuthenticatedHealthReturns()=runBlocking<Unit> {
        assertNull(coordinator.state.value.connectedPeer);assertEquals(0,requests.get())
        coordinator.foregroundChanged(true);val state=await{it.connectedPeer==peer.id}
        assertEquals(1L,state.document!!.peer(peer.id).remoteRevision);assertTrue(requests.get()>0)
    }
    @Test fun backgroundImmediatelyClearsLiveHealthAndDiscovery()=runBlocking<Unit> {
        coordinator.foregroundChanged(true);await{it.connectedPeer==peer.id}
        coordinator.foregroundChanged(false);assertNull(coordinator.state.value.connectedPeer);assertFalse(network.foreground);assertTrue(discovery.stops.get()>0)
        await{!it.foreground&&!it.loading}
    }
    @Test fun missingKeyLatchesNetworkUntilAnExplicitSuccessfulReload()=runBlocking<Unit> {
        val original=keys.key;keys.key=null;coordinator.foregroundChanged(true);await{it.error=="IDENTITY_KEY_MISSING"}
        assertFalse(network.allowed);keys.key=original
        coordinator.foregroundChanged(true);await{it.document!=null}
        assertFalse(network.allowed);assertEquals(0,requests.get())
        coordinator.reload();await{it.connectedPeer==peer.id};assertTrue(network.allowed)
    }
    @Test fun disabledPreferenceNeverAutomaticallyOpensASession()=runBlocking<Unit> {
        repository.mutate { it.copy(enabled=false) };coordinator.foregroundChanged(true);await{!it.loading}
        assertFalse(network.allowed);assertEquals(0,requests.get());assertNull(coordinator.state.value.connectedPeer)
    }
    @Test fun persistedExhaustedRetryBudgetDoesNotResetOnForeground()=runBlocking<Unit> {
        repository.mutate { it.replace(peer.copy(failures=6)) };coordinator.foregroundChanged(true);await{!it.loading}
        assertEquals(0,requests.get());assertNull(coordinator.state.value.connectedPeer)
    }
    @Test fun invalidAuthenticatedHealthBlocksInsteadOfShowingConnected()=runBlocking<Unit> {
        invalidHealth=true;coordinator.foregroundChanged(true)
        val state=await{it.document?.peer(peer.id)?.blocked==true}
        assertNull(state.connectedPeer);assertEquals("IDENTITY_REJECTED",state.error)
    }
    @Test fun routeChangeClearsHintsEvenWhenStillForeground()=runBlocking<Unit> {
        coordinator.foregroundChanged(true);await{it.connectedPeer==peer.id}
        val stops=discovery.stops.get();network.eligible=false;network.onRouteChanged()
        assertTrue(discovery.stops.get()>stops);assertNull(coordinator.state.value.connectedPeer)
        await{!it.networkAvailable};assertTrue(network.foreground)
    }
    @Test fun failedRevocationNeverClaimsDurableSuccessEvenWhileClosing()=runBlocking<Unit> {
        coordinator.foregroundChanged(true);await{it.connectedPeer==peer.id}
        val observed=java.util.Collections.synchronizedList(mutableListOf<String>())
        val monitor=launch(Dispatchers.Unconfined) { coordinator.state.collect { observed.add(it.status) } }
        try {
            blob.failWrite=true;coordinator.revoke(peer.id);val state=await{it.error=="STORAGE_UNAVAILABLE"}
            assertFalse(network.allowed);assertNull(state.connectedPeer)
            assertEquals(TrustStatus.ACTIVE,state.document!!.peer(peer.id).status)
            assertFalse(observed.contains("Device locally revoked"))
        } finally { monitor.cancelAndJoin() }
    }
    @Test fun revokePersistsTombstoneAndClearsConnectedState()=runBlocking<Unit> {
        coordinator.foregroundChanged(true);await{it.connectedPeer==peer.id};coordinator.revoke(peer.id)
        val state=await{it.document?.peer(peer.id)?.status==TrustStatus.REVOKED}
        assertNull(state.connectedPeer);assertNull(state.document!!.preferredPeer);assertEquals(Permissions(),state.document.peer(peer.id).remotePermissions)
    }
    @Test fun oneExplicitPreparationCreatesAndEnablesOnceWithoutClaiming()=runBlocking<Unit> {
        keys=TestKeys();blob=TestBlob();repository=ProtectedConnectionsRepository(TestAnchor(),keys,blob)
        coordinator=ConnectionsCoordinator(repository,connector,network,discovery,foundation.settings,scope)
        coordinator.foregroundChanged(true);await{!it.loading}
        assertEquals(0,keys.creations);assertFalse(network.allowed)
        assertTrue(coordinator.preparePairingInput())
        val first=repository.read()!!;assertTrue(first.enabled);assertTrue(first.peers.isEmpty());assertTrue(network.allowed)
        assertTrue(coordinator.preparePairingInput());assertEquals(first,repository.read());assertEquals(1,keys.creations)
        assertEquals(0,requests.get());assertNull(coordinator.state.value.connectedPeer)
    }
    @Test fun existingIdentityAndSavedTrustAreReusedWithoutNetworkWork()=runBlocking<Unit> {
        repository.mutate{it.copy(enabled=false)};val before=repository.read()!!;network.foreground=true
        assertTrue(coordinator.preparePairingInput());val after=repository.read()!!
        assertEquals(before.deviceId,after.deviceId);assertEquals(before.fingerprint,after.fingerprint)
        assertEquals(before.peers,after.peers);assertEquals(before.preferredPeer,after.preferredPeer)
        assertEquals(1,keys.creations);assertTrue(after.enabled);assertEquals(0,requests.get())
    }
    @Test fun missingKeyOrStoreIsNeverRecreatedByQuickSetup()=runBlocking<Unit> {
        network.foreground=true;val bytes=blob.bytes!!.clone();keys.key=null
        assertFalse(coordinator.preparePairingInput());assertEquals("IDENTITY_KEY_MISSING",coordinator.state.value.error)
        assertEquals(1,keys.creations);assertArrayEquals(bytes,blob.bytes);assertFalse(network.allowed)
        blob.bytes=null
        assertFalse(coordinator.preparePairingInput());assertNull(blob.bytes);assertEquals(1,keys.creations)
    }
    @Test fun missingStoreWithValidKeyAlsoFailsClosed()=runBlocking<Unit> {
        network.foreground=true;blob.bytes=null
        assertFalse(coordinator.preparePairingInput());assertEquals("TRUST_STORE_MISSING",coordinator.state.value.error)
        assertEquals(1,keys.creations);assertNull(blob.bytes);assertEquals(0,requests.get())
    }
    @Test fun quickSetupRequiresForegroundAndAnEligibleRoute()=runBlocking<Unit> {
        repository.mutate{it.copy(enabled=false)};val before=repository.read()
        assertFalse(coordinator.preparePairingInput());assertEquals(before,repository.read())
        network.foreground=true;network.eligible=false
        assertFalse(coordinator.preparePairingInput());assertEquals(before,repository.read());assertEquals(0,requests.get())
    }
    private suspend fun interruptedPreparation(interrupt:()->Unit) {
        repository.mutate{it.copy(enabled=false)};network.foreground=true
        val entered=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>()
        val delayed=object:TrustRepository by repository {
            override suspend fun identity():TransportIdentity { entered.complete(Unit);resume.await();return repository.identity() }
        }
        coordinator=ConnectionsCoordinator(delayed,connector,network,discovery,foundation.settings,scope)
        coroutineScope {
            val preparing=async{coordinator.preparePairingInput()};withTimeout(5000){entered.await()}
            interrupt();resume.complete(Unit);assertFalse(withTimeout(5000){preparing.await()})
        }
        assertFalse(repository.read()!!.enabled);assertEquals(1,keys.creations);assertEquals(0,requests.get())
    }
    @Test fun backgroundAndImmediateReturnDropLateScannerReadiness()=runBlocking<Unit> {
        interruptedPreparation { coordinator.foregroundChanged(false);coordinator.foregroundChanged(true) }
    }
    @Test fun routeChangeAndReturnDropLateScannerReadiness()=runBlocking<Unit> {
        interruptedPreparation { network.eligible=false;network.onRouteChanged();network.eligible=true;network.onRouteChanged() }
    }
    @Test fun foregroundFenceIsCapturedBeforeWaitingForTheCommandMutex()=runBlocking<Unit> {
        repository.mutate{it.copy(enabled=false)};network.foreground=true
        val entered=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>()
        val delayed=object:TrustRepository by repository {
            override suspend fun initialize():TrustDocument { entered.complete(Unit);resume.await();return repository.initialize() }
        }
        coordinator=ConnectionsCoordinator(delayed,connector,network,discovery,foundation.settings,scope)
        coordinator.initialize();withTimeout(5000){entered.await()}
        val preparing=async(start=CoroutineStart.UNDISPATCHED){coordinator.preparePairingInput()}
        coordinator.foregroundChanged(false);coordinator.foregroundChanged(true);resume.complete(Unit)
        assertFalse(withTimeout(5000){preparing.await()});assertFalse(repository.read()!!.enabled)
    }
    @Test fun pendingTrustNeverReplaysAClaimOrAcknowledgementOnQuickSetup()=runBlocking<Unit> {
        network.foreground=true
        for(status in listOf(TrustStatus.CLAIMING,TrustStatus.WAITING,TrustStatus.APPROVED,TrustStatus.ACK_PENDING)) {
            repository.mutate{it.copy(preferredPeer=null,peers=listOf(peer.copy(status=status)))}
            val before=repository.read();assertFalse(coordinator.preparePairingInput());assertEquals(before,repository.read())
        }
        assertEquals(0,requests.get());assertEquals(1,keys.creations)
    }
    @Test fun activeApprovalChecksAreVisibleButFinishAndPermissionsStayExplicit()=runBlocking<Unit> {
        repository.mutate{it.copy(preferredPeer=null,peers=listOf(peer.copy(status=TrustStatus.WAITING)))}
        coordinator.foregroundChanged(true);await{!it.loading};assertEquals(0,proofs.get())
        coordinator.recover(peer.id);await{it.pairingInProgress && it.status.contains("Checking automatically")}
        assertFalse(coordinator.preparePairingInput());assertTrue(coordinator.state.value.pairingInProgress)
        ownerApproved=true;val approved=await{it.document?.peer(peer.id)?.status==TrustStatus.APPROVED && !it.pairingInProgress}
        assertEquals(Permissions(),approved.document!!.peer(peer.id).remotePermissions)
        assertNull(approved.connectedPeer);assertEquals(0,acknowledgements.get())
        coordinator.finish(peer.id,false);await{it.error=="LOCAL_CONFIRMATION_REQUIRED" && !it.pairingInProgress}
        assertEquals(0,acknowledgements.get());assertEquals(TrustStatus.APPROVED,repository.read()!!.peer(peer.id).status)
        coordinator.finish(peer.id,true);await{it.connectedPeer==peer.id && !it.pairingInProgress}
        assertEquals(1,acknowledgements.get());assertEquals(1,keys.creations)
    }
    @Test fun interruptedApprovalDoesNotPretendToKeepPollingOrResumeItSilently()=runBlocking<Unit> {
        repository.mutate{it.copy(preferredPeer=null,peers=listOf(peer.copy(status=TrustStatus.WAITING)))}
        coordinator.foregroundChanged(true);await{!it.loading};coordinator.recover(peer.id)
        await{it.pairingInProgress && it.status.contains("Checking automatically")}
        coordinator.foregroundChanged(false);assertFalse(coordinator.state.value.pairingInProgress)
        await{!it.foreground && !it.busy};val count=proofs.get()
        coordinator.foregroundChanged(true);await{it.foreground && !it.loading}
        delay(2200);assertEquals(count,proofs.get());assertFalse(coordinator.state.value.pairingInProgress)
        assertEquals(TrustStatus.WAITING,repository.read()!!.peer(peer.id).status)
    }

}
