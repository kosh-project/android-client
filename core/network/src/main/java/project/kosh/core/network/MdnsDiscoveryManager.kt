package project.kosh.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import android.os.Build
import android.util.Log
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Service orchestrator managing native Android Multicast DNS (mDNS / DNS-SD) discovery.
 *
 * Implements zero-configuration discovery over UDP port 5353 using Android's [NsdManager].
 * Automatically discovers running Kosh servers on the local Wi-Fi network without requiring
 * users to manually enter IP addresses.
 *
 * ### Android Hardware & Concurrency Invariants
 * 1. **Multicast Lock:** Wi-Fi chipsets on modern Android devices drop multicast UDP packets
 *    by default during power-saving states. This manager acquires a [WifiManager.MulticastLock]
 *    prior to starting discovery and releases it when stopping.
 * 2. **Sequential Resolution Queue:** Prior to Android 12+, calling [NsdManager.resolveService]
 *    while a resolution is already in flight fails with `FAILURE_ALREADY_ACTIVE`. This manager
 *    queues all service discovery callbacks into a sequential coroutine [Channel] to guarantee
 *    serialized, safe resolutions.
 * 3. **Dual-Stack Preference:** Prefers IPv4 ([Inet4Address]) over IPv6 to avoid broken link-local
 *    IPv6 routing common in home routers.
 *
 * @param context Android application context for resolving system services.
 */
@Singleton
class MdnsDiscoveryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val nsdManager: NsdManager? by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }

    private var multicastLock: WifiManager.MulticastLock? = null
    private var lockRefCount = 0

    /**
     * Acquires the Wi-Fi multicast lock to permit reception of UDP 5353 multicast frames.
     */
    @Synchronized
    fun acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
                    setReferenceCounted(false)
                }
            }
            lockRefCount++
            if (lockRefCount == 1) {
                multicastLock?.acquire()
                Log.d(TAG, "Acquired Wi-Fi MulticastLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire MulticastLock: ${e.message}")
        }
    }

    /**
     * Releases the active Wi-Fi multicast lock to avoid battery drain.
     */
    @Synchronized
    fun releaseMulticastLock() {
        try {
            if (lockRefCount > 0) {
                lockRefCount--
                if (lockRefCount == 0) {
                    multicastLock?.let {
                        if (it.isHeld) {
                            it.release()
                            Log.d(TAG, "Released Wi-Fi MulticastLock")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MulticastLock: ${e.message}")
        }
    }

    /**
     * Discovers active Kosh servers on the local network as a continuous cold [Flow].
     *
     * Discovers both HTTPS (`_https._tcp`) and fallback HTTP (`_http._tcp`) announcements.
     * Manages multicast lock acquisition and cleanup automatically upon collection termination.
     *
     * @return [Flow] emitting the updated list of discovered servers whenever a server is found,
     *   resolved, or lost.
     */
    fun discoverServers(): Flow<List<DiscoveredServer>> = callbackFlow {
        val manager = nsdManager
        if (manager == null) {
            Log.w(TAG, "NsdManager is null; mDNS discovery unavailable")
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        acquireMulticastLock()

        val discoveredServers = ConcurrentHashMap<String, DiscoveredServer>()
        val resolveChannel = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        // Sequential resolution worker to prevent FAILURE_ALREADY_ACTIVE concurrency crashes
        val resolutionWorker = launch(Dispatchers.IO) {
            for (serviceInfo in resolveChannel) {
                val server = resolveServiceSafely(manager, serviceInfo)
                if (server != null) {
                    val existing = discoveredServers[server.name]
                    // If an existing entry has TLS enabled and targets the same host, do not overwrite with unencrypted HTTP
                    if (existing != null && existing.isTls && !server.isTls && existing.host == server.host) {
                        continue
                    }
                    discoveredServers[server.name] = server

                    // Register domain and hostname mappings with KoshDns for immediate OkHttp resolution
                    KoshDns.setAddress(server.name, server.host)
                    KoshDns.setAddress("${server.name}.local", server.host)
                    KoshDns.setAddress(server.domain, server.host)
                    if (!server.hostname.isNullOrBlank()) {
                        KoshDns.setAddress(server.hostname, server.host)
                        KoshDns.setAddress("${server.hostname}.local", server.host)
                    }

                    Log.d(TAG, "Discovered active server: ${server.name} at ${server.baseUrl}")
                    trySend(discoveredServers.values.toList())
                }
            }
        }

        val discoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()

        val serviceTypes = listOf(SERVICE_TYPE_HTTPS, SERVICE_TYPE_HTTP)
        for (serviceType in serviceTypes) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "Discovery started for service type: $regType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    Log.d(TAG, "Service found: name=${service.serviceName}, type=${service.serviceType}")
                    resolveChannel.trySend(service)
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    val serviceName = service.serviceName ?: return
                    Log.d(TAG, "Service lost: $serviceName on type $serviceType")
                    val existing = discoveredServers[serviceName]
                    if (existing != null) {
                        val isHttpsLoss = serviceType == SERVICE_TYPE_HTTPS ||
                                service.serviceType?.contains("_https", ignoreCase = true) == true
                        // Protect TLS-secured discovered servers from unencrypted HTTP lost events
                        if (existing.isTls == isHttpsLoss) {
                            discoveredServers.remove(serviceName)
                            trySend(discoveredServers.values.toList())
                        }
                    }
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "Discovery stopped for service type: $serviceType")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "Failed to start discovery for $serviceType: errorCode=$errorCode")
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "Failed to stop discovery for $serviceType: errorCode=$errorCode")
                }
            }

            discoveryListeners.add(listener)
            try {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                Log.w(TAG, "discoverServices failed for $serviceType: ${e.message}")
            }
        }

        awaitClose {
            for (listener in discoveryListeners) {
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "stopServiceDiscovery error: ${e.message}")
                }
            }
            resolveChannel.close()
            resolutionWorker.cancel()
            releaseMulticastLock()
        }
    }

    /**
     * Resolves an [NsdServiceInfo] instance sequentially via [suspendCancellableCoroutine]
     * bounded by a 5-second timeout to prevent unresponsive mDNS endpoints from wedging the resolution queue.
     *
     * On Android 14+ (API 34+), utilizes [NsdManager.registerServiceInfoCallback] as recommended
     * by the platform architecture to avoid deprecated, flaky resolveService calls.
     */
    private suspend fun resolveServiceSafely(
        manager: NsdManager,
        serviceInfo: NsdServiceInfo
    ): DiscoveredServer? = kotlinx.coroutines.withTimeoutOrNull(5000L) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            suspendCancellableCoroutine { continuation ->
                val executor = Dispatchers.IO.asExecutor()

                lateinit var callback: NsdManager.ServiceInfoCallback
                callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceUpdated(resolvedInfo: NsdServiceInfo) {
                        Log.d(TAG, "Service updated via ServiceInfoCallback: ${resolvedInfo.serviceName}")
                        val server = parseServiceInfo(resolvedInfo)
                        if (server != null) {
                            try {
                                manager.unregisterServiceInfoCallback(this)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to unregister ServiceInfoCallback: ${e.message}")
                            }
                            if (continuation.isActive) {
                                continuation.resume(server)
                            }
                        } else {
                            Log.d(TAG, "Service update lacked routable IPv4/global IPv6 address; waiting for further updates...")
                        }
                    }

                    override fun onServiceLost() {
                        Log.d(TAG, "Service lost during resolution: ${serviceInfo.serviceName}")
                        try {
                            manager.unregisterServiceInfoCallback(this)
                        } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    override fun onServiceInfoCallbackRegistered() {
                        Log.d(TAG, "ServiceInfoCallback registered for ${serviceInfo.serviceName}")
                    }

                    override fun onServiceInfoCallbackUnregistered() {
                        Log.d(TAG, "ServiceInfoCallback unregistered for ${serviceInfo.serviceName}")
                    }

                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        Log.w(TAG, "ServiceInfoCallback registration failed for ${serviceInfo.serviceName}: errorCode=$errorCode")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    try {
                        manager.unregisterServiceInfoCallback(callback)
                    } catch (_: Exception) {}
                }

                try {
                    manager.registerServiceInfoCallback(serviceInfo, executor, callback)
                } catch (e: Exception) {
                    Log.w(TAG, "registerServiceInfoCallback invocation failed: ${e.message}")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            suspendCancellableCoroutine { continuation ->
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                        Log.w(TAG, "Legacy resolveService failed: errorCode=$errorCode")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    override fun onServiceResolved(resolvedInfo: NsdServiceInfo?) {
                        Log.d(TAG, "Legacy resolveService resolved: ${resolvedInfo?.serviceName}")
                        if (continuation.isActive) {
                            val server = resolvedInfo?.let { parseServiceInfo(it) }
                            continuation.resume(server)
                        }
                    }
                }

                try {
                    manager.resolveService(serviceInfo, resolveListener)
                } catch (e: Exception) {
                    Log.w(TAG, "Legacy resolveService error: ${e.message}")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
            }
        }
    }

    /**
     * Parses an [NsdServiceInfo] into a structured [DiscoveredServer].
     * Returns null if no routable IPv4 or global IPv6 address can be resolved.
     */
    @Suppress("DEPRECATION")
    fun parseServiceInfo(info: NsdServiceInfo): DiscoveredServer? {
        val hostAddress = resolveHostAddress(info) ?: return null

        val rawPort = info.port
        val port = if (rawPort in 1..65535) rawPort else ServerConfig.DEFAULT_PORT
        val txtAttributes = mutableMapOf<String, String>()

        try {
            for ((key, bytes) in info.attributes) {
                if (bytes != null) {
                    txtAttributes[key] = String(bytes, Charsets.UTF_8)
                }
            }
        } catch (_: Throwable) {}

        val isTls = (txtAttributes["tls"]?.lowercase() == "true") ||
                (info.serviceType?.contains("_https", ignoreCase = true) == true)
        val version = txtAttributes["version"]
        val hostname = txtAttributes["hostname"]

        return DiscoveredServer(
            name = info.serviceName?.ifBlank { null } ?: "kosh-server",
            host = hostAddress,
            port = port,
            isTls = isTls,
            version = version,
            hostname = hostname,
            txtRecords = txtAttributes
        )
    }

    /**
     * Resolves the primary network host IP address from [NsdServiceInfo].
     *
     * Dual-stack preference:
     * 1. Inspects both [NsdServiceInfo.getHostAddresses] and [NsdServiceInfo.getHost] to evaluate all advertised addresses.
     * 2. Prefers non-loopback IPv4 ([Inet4Address]) over IPv6 to avoid broken link-local IPv6 routing common on home Wi-Fi.
     * 3. Falls back to any IPv4, then global unicast IPv6.
     * 4. Checks [KoshDns] for a known cached routable IP for this service name.
     * 5. Rejects unroutable link-local IPv6 (fe80::) addresses and returns null so caller can await IPv4 updates.
     */
    @Suppress("DEPRECATION")
    private fun resolveHostAddress(info: NsdServiceInfo): String? {
        val candidates: MutableList<InetAddress> = mutableListOf()
        try {
            val list = info.hostAddresses
            if (!list.isNullOrEmpty()) {
                candidates.addAll(list)
            }
        } catch (_: Throwable) {}

        try {
            val h = info.host
            if (h != null && !candidates.contains(h)) {
                candidates.add(h)
            }
        } catch (_: Throwable) {}

        Log.d(TAG, "resolveHostAddress candidates: ${candidates.map { "${it.javaClass.simpleName}:${it.hostAddress}" }}")

        // 1. Non-loopback, non-wildcard IPv4 address (e.g. 192.168.1.5)
        val routableIpv4 = candidates.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isAnyLocalAddress }
        if (routableIpv4 != null) {
            val addr = routableIpv4.hostAddress
            if (!addr.isNullOrBlank()) return addr
        }

        // 2. Any IPv4 address
        val anyIpv4 = candidates.firstOrNull { it is Inet4Address }
        if (anyIpv4 != null) {
            val addr = anyIpv4.hostAddress
            if (!addr.isNullOrBlank()) return addr
        }

        // 3. Routable global IPv6 address (non-link-local, non-loopback)
        val globalIpv6 = candidates.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress && !it.isLoopbackAddress && !it.isAnyLocalAddress }
        if (globalIpv6 != null) {
            val addr = globalIpv6.hostAddress?.substringBefore('%')
            if (!addr.isNullOrBlank()) return addr
        }

        // 4. Any non-link-local IPv6 address (e.g. ::1 loopback)
        val nonLinkLocalIpv6 = candidates.firstOrNull { it is Inet6Address && !it.isLinkLocalAddress }
        if (nonLinkLocalIpv6 != null) {
            val addr = nonLinkLocalIpv6.hostAddress?.substringBefore('%')
            if (!addr.isNullOrBlank()) return addr
        }

        // 5. Cached routable IP from KoshDns if previously resolved for this server
        val serviceName = info.serviceName
        if (!serviceName.isNullOrBlank()) {
            val cachedIp = KoshDns.getResolvedIp(serviceName) ?: KoshDns.getResolvedIp("$serviceName.local")
            if (!cachedIp.isNullOrBlank() && !cachedIp.startsWith("fe80:", ignoreCase = true)) {
                return cachedIp
            }
        }

        // 6. Strictly reject link-local IPv6 (fe80::) addresses lacking interface scope
        return null
    }

    companion object {
        const val SERVICE_TYPE_HTTPS = "_https._tcp"
        const val SERVICE_TYPE_HTTP = "_http._tcp"
        const val MULTICAST_LOCK_TAG = "kosh_mdns_lock"
        private const val TAG = "MdnsDiscovery"
    }
}
