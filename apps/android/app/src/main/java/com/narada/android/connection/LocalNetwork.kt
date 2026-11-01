package com.narada.android.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.narada.connection.Endpoint
import com.narada.connection.SocketSource
import com.narada.connection.demand
import com.narada.connection.fail
import com.narada.connection.sha256
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.Socket
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ConnectionNetworkPolicy {
    var foreground: Boolean
    var allowed: Boolean
    var onRouteChanged: () -> Unit
    fun available(): Boolean
}
interface DiscoveryControl { fun start(); fun stop() }

@Singleton
class LocalNetwork @Inject constructor(@ApplicationContext context: Context) : SocketSource, ConnectionNetworkPolicy {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile override var foreground = false
    @Volatile override var allowed = false
    @Volatile override var onRouteChanged: () -> Unit = {}
    private var observed: Pair<Network, List<String>>? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = changed()
        @Synchronized private fun changed() {
            val next = eligibleNetwork()?.let { route -> route to connectivity.getLinkProperties(route)?.linkAddresses.orEmpty().map { it.toString() }.sorted() }
            if (observed != next) { observed = next; onRouteChanged() }
        }
    }
    init { connectivity.registerDefaultNetworkCallback(callback) }
    override fun available() = eligibleNetwork() != null
    fun eligibleNetwork(): Network? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val addresses = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
        if (addresses.none { it.address is Inet4Address && runCatching { Endpoint.parse("${it.address.hostAddress}:1") }.isSuccess && !it.address.isLoopbackAddress }) return null
        return network
    }
    override fun create(endpoint: Endpoint): Socket {
        demand(foreground && allowed, "NETWORK_DISABLED")
        // Production Android never routes a computer connection to this phone's loopback.
        demand(!endpoint.address().isLoopbackAddress, "ENDPOINT_NOT_LOCAL")
        val network = eligibleNetwork() ?: fail("LOCAL_NETWORK_UNAVAILABLE")
        val socket = Socket()
        try { network.bindSocket(socket); return socket } catch (_: Exception) { socket.close(); fail("LOCAL_NETWORK_UNAVAILABLE") }
    }
}

data class DiscoveryHint(val id: String, val endpoint: String, val observedAt: Long)
data class DiscoveryState(val scanning: Boolean = false, val hints: List<DiscoveryHint> = emptyList(), val error: Boolean = false)

/** NSD results are bounded, transient, untrusted endpoint suggestions, never identities. */
@Singleton
class LocalDiscovery @Inject constructor(@ApplicationContext context: Context, private val network: LocalNetwork) : DiscoveryControl {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(DiscoveryState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var listener: NsdManager.DiscoveryListener? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val seen = mutableSetOf<String>()
    private var resolving = false
    private var activeResolver: NsdManager.ResolveListener? = null
    private var scanNetwork: Network? = null
    override fun start() { main.post { begin() } }
    override fun stop() { main.post { end() } }
    private fun end() {
        generation++
        activeResolver?.let { if (Build.VERSION.SDK_INT >= 34) runCatching { nsd.stopServiceResolution(it) } }; activeResolver = null
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null
        queue.clear(); seen.clear(); resolving = false; scanNetwork = null
        mutable.value = DiscoveryState() // Do not keep stale or lost-interface hints.
    }
    private fun begin() {
        end()
        val selected = network.eligibleNetwork()
        if (!network.foreground || !network.allowed || selected == null) { mutable.value = DiscoveryState(error = true); return }
        scanNetwork = selected
        val ticket = generation
        val handler = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { main.post { if (generation == ticket) { end(); mutable.value = DiscoveryState(error = true) } } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) { main.post { if (generation == ticket) { seen.remove(info.serviceName); mutable.value = mutable.value.copy(hints = mutable.value.hints.filterNot { it.id == hintId(info.serviceName) }) } } }
            override fun onServiceFound(info: NsdServiceInfo) { main.post {
                if (generation != ticket || info.serviceType.trimEnd('.') != "_narada._tcp" || info.serviceName.length > 128 || seen.size >= 32 || !seen.add(info.serviceName)) return@post
                if (queue.size < 16) { queue.addLast(info); resolveNext(ticket) }
            } }
        }
        listener = handler; mutable.value = DiscoveryState(scanning = true)
        try {
            if (Build.VERSION.SDK_INT >= 33) nsd.discoverServices("_narada._tcp.", NsdManager.PROTOCOL_DNS_SD, selected, Executor { main.post(it) }, handler)
            else nsd.discoverServices("_narada._tcp.", NsdManager.PROTOCOL_DNS_SD, handler)
            main.postDelayed({ if (generation == ticket) {
                val hints = mutable.value.hints
                end(); mutable.value = DiscoveryState(hints = hints)
                val finished = generation
                main.postDelayed({ if (generation == finished) mutable.value = DiscoveryState() }, 30_000)
            } }, 20_000)
        } catch (_: Exception) { end(); mutable.value = DiscoveryState(error = true) }
    }
    @Suppress("DEPRECATION") // API 29–33 compatibility; one bounded resolve at a time, stopped by generation fences.
    private fun resolveNext(ticket: Long) {
        if (resolving || generation != ticket || queue.isEmpty()) return
        if (!network.foreground || !network.allowed || network.eligibleNetwork() != scanNetwork) { end(); return }
        val service = queue.removeFirst(); resolving = true
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { main.post { complete(null) } }
            override fun onServiceResolved(info: NsdServiceInfo) { main.post { complete(info) } }
            private fun complete(info: NsdServiceInfo?) {
                if (generation != ticket) return
                resolving = false; activeResolver = null
                if (!network.foreground || !network.allowed || network.eligibleNetwork() != scanNetwork) { end(); return }
                if (info != null && seen.contains(info.serviceName) && network.eligibleNetwork() == scanNetwork && info.attributes["v"]?.contentEquals(byteArrayOf(49)) == true) {
                    val host = info.host
                    val value = if (host is Inet4Address && !host.isLoopbackAddress) runCatching { Endpoint.parse("${host.hostAddress}:${info.port}") }.getOrNull() else null
                    if (value != null) {
                        val hint = DiscoveryHint(hintId(info.serviceName), value.authority, android.os.SystemClock.elapsedRealtime())
                        mutable.value = mutable.value.copy(hints = (mutable.value.hints.filterNot { it.id == hint.id } + hint).takeLast(32))
                    }
                }
                resolveNext(ticket)
            }
        }
        activeResolver = resolver
        try { nsd.resolveService(service, resolver) } catch (_: Exception) { resolving = false; activeResolver = null; resolveNext(ticket) }
    }
    private fun hintId(name: String) = sha256(name.toByteArray()).take(24)
}
