package project.kosh.core.network

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Custom OkHttp [Dns] resolver with dynamic Multicast DNS (mDNS) resolution caching.
 *
 * On Android, the platform libc/netd resolver queries system unicast DNS and rejects `.local`
 * mDNS hostnames (such as `kosh.local`) with [UnknownHostException].
 *
 * [KoshDns] bridges mDNS discovery by maintaining a live map of resolved local services
 * from [MdnsDiscoveryManager], ensuring OkHttp, TLS SNI, and WebAuthn operations against
 * `kosh.local` route directly to the active routable IPv4 address.
 *
 * Also prioritizes routable IPv4 addresses over link-local IPv6 addresses (`fe80::...`) lacking
 * interface zone scopes to prevent connection refused/timeout errors.
 */
object KoshDns : Dns {

    private val dynamicMappings = ConcurrentHashMap<String, String>()

    /**
     * Registers a known IP address mapping for a hostname or LAN domain (e.g. `kosh.local` -> `192.168.1.5`).
     */
    fun setAddress(hostname: String, ipAddress: String) {
        val cleanHost = hostname.trim().lowercase()
        val cleanIp = ipAddress.trim()
        if (cleanHost.isBlank() || cleanIp.isBlank()) return
        if (cleanIp.startsWith("fe80:", ignoreCase = true)) {
            // Reject unroutable link-local IPv6 addresses lacking interface scopes
            return
        }

        dynamicMappings[cleanHost] = cleanIp
        if (!cleanHost.endsWith(".local") && !cleanHost.contains(".")) {
            dynamicMappings["$cleanHost.local"] = cleanIp
        } else if (cleanHost.endsWith(".local")) {
            val bare = cleanHost.removeSuffix(".local")
            if (bare.isNotBlank()) {
                dynamicMappings[bare] = cleanIp
            }
        }
    }

    /**
     * Retrieves the active mapped IP address for the given hostname, if registered.
     */
    fun getResolvedIp(hostname: String): String? {
        val cleanHost = hostname.trim().lowercase()
        return dynamicMappings[cleanHost]
            ?: if (cleanHost.endsWith(".local")) dynamicMappings[cleanHost.removeSuffix(".local")] else null
    }

    /**
     * Removes a mapped hostname from the dynamic cache.
     */
    fun removeAddress(hostname: String) {
        val cleanHost = hostname.trim().lowercase()
        dynamicMappings.remove(cleanHost)
        dynamicMappings.remove("$cleanHost.local")
        if (cleanHost.endsWith(".local")) {
            dynamicMappings.remove(cleanHost.removeSuffix(".local"))
        }
    }

    /**
     * Clears all dynamic host mappings.
     */
    fun clear() {
        dynamicMappings.clear()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val key = hostname.trim().lowercase()
        val mappedIp = dynamicMappings[key]
            ?: if (key.endsWith(".local")) dynamicMappings[key.removeSuffix(".local")] else null

        if (mappedIp != null && !mappedIp.startsWith("fe80:", ignoreCase = true)) {
            try {
                return listOf(InetAddress.getByName(mappedIp))
            } catch (_: Exception) {
                // If cached mapping fails to parse, proceed to system lookup
            }
        }

        val addresses = try {
            Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            // Fallback for .local domains when platform resolver fails
            val fallback = dynamicMappings[key]
                ?: if (key.endsWith(".local")) dynamicMappings[key.removeSuffix(".local")] else null
            if (fallback != null && !fallback.startsWith("fe80:", ignoreCase = true)) {
                try {
                    return listOf(InetAddress.getByName(fallback))
                } catch (_: Exception) {}
            }
            throw e
        }

        val ipv4Addresses = addresses.filterIsInstance<Inet4Address>()
        if (ipv4Addresses.isNotEmpty()) {
            val nonLinkLocalIpv6 = addresses.filter { it is Inet6Address && !it.isLinkLocalAddress }
            return ipv4Addresses + nonLinkLocalIpv6
        }
        val nonLinkLocal = addresses.filter { it !is Inet6Address || !it.isLinkLocalAddress }
        if (nonLinkLocal.isNotEmpty()) {
            return nonLinkLocal
        }
        return addresses
    }
}
