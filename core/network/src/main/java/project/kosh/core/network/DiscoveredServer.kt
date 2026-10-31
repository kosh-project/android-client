package project.kosh.core.network

/**
 * Discovered Kosh server instance broadcast via Multicast DNS (mDNS / DNS-SD).
 *
 * Advertised by the server over UDP port 5353 using standard DNS-SD service descriptors
 * (`_https._tcp` or `_http._tcp`).
 *
 * @property name Instance name advertised in mDNS announcement (e.g. "kosh-server").
 * @property host Resolved IPv4, IPv6 address, or LAN hostname (e.g. "192.168.1.150").
 * @property port TCP port on which the server is listening (e.g. 6969).
 * @property isTls True if server advertises TLS encryption via `_https._tcp` or `tls=true` TXT record.
 * @property version Server software version reported in TXT record (e.g. "1.0.0"), or null if absent.
 * @property hostname Machine hostname reported in TXT record (e.g. "kosh-server"), or null if absent.
 * @property txtRecords Map of all key-value attributes decoded from the DNS-SD TXT record.
 */
data class DiscoveredServer(
    val name: String,
    val host: String,
    val port: Int,
    val isTls: Boolean,
    val version: String? = null,
    val hostname: String? = null,
    val txtRecords: Map<String, String> = emptyMap()
) {
    /**
     * Resolves the full base URL for this discovered server (e.g. `https://192.168.1.150:6969`).
     * Formats IPv6 addresses with RFC 3986 square brackets if not already enclosed.
     */
    val baseUrl: String
        get() {
            val scheme = if (isTls) "https" else "http"
            val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            return "$scheme://$formattedHost:$port"
        }

    /**
     * Resolves the mDNS domain name (e.g. `kosh.local`) suitable for WebAuthn RP ID scoping.
     */
    val domain: String
        get() = hostname?.let { if (it.endsWith(".local")) it else "$it.local" }
            ?: if (name.isNotBlank() && !name.contains(" ")) "${name.lowercase()}.local" else host
}

