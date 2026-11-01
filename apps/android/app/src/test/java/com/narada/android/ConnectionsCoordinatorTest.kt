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
    private val requests=AtomicInteger()
    @Volatile private var invalidHealth=false
    @Before fun setup()=runBlocking<Unit> {
        foundation=TestFixture();scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        keys=TestKeys();blob=TestBlob();repository=ProtectedConnectionsRepository(TestAnchor(),keys,blob);repository.initialize()
        val host=AndroidTestIdentities.create(true)
        peer=TrustedPeer(host.deviceId,host.fingerprint,Base64.getEncoder().encodeToString(host.certificate.encoded),"Fixture desktop", "windows","192.168.0.10:12345",UUID.randomUUID().toString(),UUID.randomUUID().toString(),System.currentTimeMillis()+120000,TrustStatus.ACTIVE)
        repository.mutate { it.copy(enabled=true,preferredPeer=peer.id,peers=listOf(peer)) }
        network=NetworkPolicy();discovery=Discovery()
        val connector=object:PeerConnector {
            override fun closeAll() {}
            override fun connect(endpoint:Endpoint,pin:PeerPin,identity:TransportIdentity?)=object:PeerSession {
                override val certificate=host.certificate.encoded
                override fun close(){}
                override fun request(path:String,body:ByteArray):ByteArray {
                    requests.incrementAndGet();assertEquals("/api/v1/health",path)
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
}
