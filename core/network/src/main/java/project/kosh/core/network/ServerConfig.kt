package project.kosh.core.network

/**
 * Immutable configuration model representing the active server connection topology and security parameters.
 *
 * @property host Hostname, IPv4, or IPv6 address of the server (e.g. `10.0.2.2`, `192.168.1.150`, `kosh-server.local`).
 * @property port TCP port on which the server listener is running (canonical default: 6969).
 * @property useTls Whether to establish an encrypted TLS (HTTPS) transport or unencrypted HTTP.
 * @property pinnedCertFingerprintSha256 The 32-byte SHA-256 fingerprint of the server's leaf certificate for TOFU pinning.
 */
data class ServerConfig(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val useTls: Boolean = DEFAULT_USE_TLS,
    val pinnedCertFingerprintSha256: ByteArray? = null
) {
    init {
        require(port in 1..65535) { "Port must be in range 1..65535, got $port" }
        require(pinnedCertFingerprintSha256 == null || pinnedCertFingerprintSha256.size == 32) {
            "Certificate fingerprint must be exactly 32 bytes, got ${pinnedCertFingerprintSha256?.size}"
        }
    }

    /**
     * Resolves scheme, host, and port into an HTTP/HTTPS root base URL (e.g. `https://192.168.1.150:6969`).
     * Formats IPv6 addresses with RFC 3986 square brackets if not already enclosed.
     */
    val baseUrl: String
        get() {
            val scheme = if (useTls) "https" else "http"
            val formattedHost = if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
            return "$scheme://$formattedHost:$port"
        }

    /**
     * Hex-encoded SHA-256 certificate fingerprint (64 lowercase hex chars), or null if unpinned.
     */
    val fingerprintHex: String?
        get() = pinnedCertFingerprintSha256?.joinToString("") { "%02x".format(it) }

    /**
     * Base64-encoded SHA-256 certificate fingerprint formatted as `sha256/<base64>`, or null if unpinned.
     */
    val fingerprintBase64: String?
        get() = pinnedCertFingerprintSha256?.let {
            val b64 = java.util.Base64.getEncoder().encodeToString(it)
            "sha256/$b64"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ServerConfig

        if (host != other.host) return false
        if (port != other.port) return false
        if (useTls != other.useTls) return false
        if (pinnedCertFingerprintSha256 != null) {
            if (other.pinnedCertFingerprintSha256 == null) return false
            if (!pinnedCertFingerprintSha256.contentEquals(other.pinnedCertFingerprintSha256)) return false
        } else if (other.pinnedCertFingerprintSha256 != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + useTls.hashCode()
        result = 31 * result + (pinnedCertFingerprintSha256?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val DEFAULT_HOST = "10.0.2.2"
        const val DEFAULT_PORT = 23342
        const val DEFAULT_USE_TLS = false

        const val HOST_EMULATOR = "10.0.2.2"
        const val HOST_MDNS = "kosh.local"
        const val HOST_LAN_DEFAULT = "192.168.1.5"
        const val HOST_LOCALHOST = "127.0.0.1"
        const val PORT_DEV = 23342
        const val PORT_PROD = 6969
    }
}
