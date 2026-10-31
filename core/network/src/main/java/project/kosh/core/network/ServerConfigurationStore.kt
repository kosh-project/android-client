package project.kosh.core.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hardware-backed secure persistence store for server connection topology and TLS certificate pinning.
 *
 * Persists the server host address, port, TLS activation state, and 32-byte SHA-256 certificate
 * fingerprint using [EncryptedSharedPreferences] backed by AndroidKeyStore.
 *
 * Also maintains an in-memory [StateFlow] providing real-time reactive updates to OkHttp interceptors,
 * [KoshPinnedTrustManager], and UI pairing flows.
 *
 * @param prefs Storage provider for persisting encrypted configuration values.
 */
@Singleton
class ServerConfigurationStore(
    private val prefs: SharedPreferences
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        createEncryptedSharedPreferences(context)
    )

    private val _serverConfig = MutableStateFlow(loadConfig())

    /**
     * Observable [StateFlow] emitting the active [ServerConfig] whenever updated.
     */
    @get:JvmName("getServerConfigFlow")
    val serverConfig: StateFlow<ServerConfig> = _serverConfig.asStateFlow()

    private fun loadConfig(): ServerConfig {
        val rawHost = prefs.getString(KEY_HOST, null)?.ifBlank { null }
        val host = if (rawHost != null && !rawHost.startsWith("fe80:", ignoreCase = true)) {
            rawHost
        } else {
            ServerConfig.DEFAULT_HOST
        }
        val rawPort = prefs.getInt(KEY_PORT, ServerConfig.DEFAULT_PORT)
        val port = if (rawPort in 1..65535) rawPort else ServerConfig.DEFAULT_PORT
        val useTls = prefs.getBoolean(KEY_USE_TLS, ServerConfig.DEFAULT_USE_TLS)
        val fingerprintHex = prefs.getString(KEY_FINGERPRINT, null)
        val fingerprintBytes = fingerprintHex?.let { hexToBytes(it) }?.takeIf { it.size == 32 }
        val resolvedIp = prefs.getString(KEY_RESOLVED_IP, null)?.ifBlank { null }

        if (resolvedIp != null && host.isNotBlank()) {
            KoshDns.setAddress(host, resolvedIp)
        }

        return ServerConfig(
            host = host,
            port = port,
            useTls = useTls,
            pinnedCertFingerprintSha256 = fingerprintBytes
        )
    }

    /**
     * Retrieves the active [ServerConfig] synchronously.
     */
    fun getServerConfig(): ServerConfig = _serverConfig.value

    /**
     * Atomically saves the [ServerConfig] to secure storage and updates subscribers.
     *
     * @param config The new configuration state to persist.
     */
    fun saveServerConfig(config: ServerConfig) {
        prefs.edit {
            putString(KEY_HOST, config.host)
            putInt(KEY_PORT, config.port)
            putBoolean(KEY_USE_TLS, config.useTls)
            if (config.pinnedCertFingerprintSha256 != null) {
                putString(KEY_FINGERPRINT, bytesToHex(config.pinnedCertFingerprintSha256))
            } else {
                remove(KEY_FINGERPRINT)
            }
        }
        _serverConfig.value = config
    }

    /**
     * Registers a known host to IP mapping for mDNS hostname resolution.
     */
    fun registerHostIp(host: String, ip: String) {
        if (!ip.startsWith("fe80:", ignoreCase = true)) {
            KoshDns.setAddress(host, ip)
        }
    }

    /**
     * Retrieves the resolved IP address for the given hostname, if known.
     */
    fun getResolvedIp(host: String): String? = KoshDns.getResolvedIp(host)

    /**
     * Updates server network endpoint, optional certificate fingerprint, and resolved IP.
     */
    fun updateServer(
        host: String,
        port: Int,
        useTls: Boolean,
        pinnedCertFingerprintSha256: ByteArray? = null,
        resolvedIp: String? = KoshDns.getResolvedIp(host)
    ) {
        val effectiveIp = (resolvedIp ?: KoshDns.getResolvedIp(host))?.takeUnless { it.startsWith("fe80:", ignoreCase = true) }
        prefs.edit {
            putString(KEY_HOST, host)
            putInt(KEY_PORT, port)
            putBoolean(KEY_USE_TLS, useTls)
            if (pinnedCertFingerprintSha256 != null) {
                putString(KEY_FINGERPRINT, bytesToHex(pinnedCertFingerprintSha256))
            } else {
                remove(KEY_FINGERPRINT)
            }
            if (!effectiveIp.isNullOrBlank()) {
                putString(KEY_RESOLVED_IP, effectiveIp)
                KoshDns.setAddress(host, effectiveIp)
            }
        }
        _serverConfig.value = ServerConfig(
            host = host,
            port = port,
            useTls = useTls,
            pinnedCertFingerprintSha256 = pinnedCertFingerprintSha256
        )
    }

    /**
     * Updates the pinned leaf certificate fingerprint for Trust-On-First-Use (TOFU).
     */
    fun updatePinnedFingerprint(fingerprintBytes: ByteArray?) {
        val current = _serverConfig.value
        saveServerConfig(current.copy(pinnedCertFingerprintSha256 = fingerprintBytes))
    }

    /**
     * Updates the pinned leaf certificate fingerprint from a string representation
     * (hex, base64, or sha256/<base64>).
     *
     * @param fingerprintString String fingerprint or null to unpin.
     * @return True if fingerprint was parsed and saved, false if format is invalid.
     */
    fun updatePinnedFingerprint(fingerprintString: String?): Boolean {
        if (fingerprintString == null) {
            updatePinnedFingerprint(null as ByteArray?)
            return true
        }
        val bytes = parseFingerprint(fingerprintString) ?: return false
        updatePinnedFingerprint(bytes)
        return true
    }

    fun getHost(): String = _serverConfig.value.host
    fun getPort(): Int = _serverConfig.value.port
    fun isTlsEnabled(): Boolean = _serverConfig.value.useTls
    fun getPinnedFingerprint(): ByteArray? = _serverConfig.value.pinnedCertFingerprintSha256
    fun getBaseUrl(): String = _serverConfig.value.baseUrl
    fun getResolvedIp(): String? = prefs.getString(KEY_RESOLVED_IP, null)?.ifBlank { null }

    /**
     * Returns true if an explicit server host has been saved in persistent storage.
     * Treats unroutable IPv6 link-local addresses (fe80:...) as unconfigured so that
     * devices can auto-discover or adopt valid routable endpoints.
     */
    fun hasConfiguredHost(): Boolean {
        val host = prefs.getString(KEY_HOST, null) ?: return false
        return !host.startsWith("fe80:", ignoreCase = true)
    }

    /**
     * Clears all persisted server configurations and resets to defaults.
     */
    fun clear() {
        prefs.edit {
            clear()
        }
        _serverConfig.value = ServerConfig()
    }

    companion object {
        const val PREFS_NAME = "kosh_server_config"
        const val KEY_HOST = "server_host"
        const val KEY_PORT = "server_port"
        const val KEY_USE_TLS = "server_use_tls"
        const val KEY_FINGERPRINT = "server_cert_fingerprint_sha256"
        const val KEY_RESOLVED_IP = "server_resolved_ip"

        fun bytesToHex(bytes: ByteArray): String {
            val hexChars = "0123456789abcdef"
            val result = StringBuilder(bytes.size * 2)
            for (byte in bytes) {
                val b = byte.toInt() and 0xFF
                result.append(hexChars[b ushr 4])
                result.append(hexChars[b and 0x0F])
            }
            return result.toString()
        }

        fun hexToBytes(hex: String): ByteArray? {
            val cleanHex = hex.trim()
            if (cleanHex.length % 2 != 0) return null
            val len = cleanHex.length
            val result = ByteArray(len / 2)
            for (i in 0 until len step 2) {
                val d1 = Character.digit(cleanHex[i], 16)
                val d2 = Character.digit(cleanHex[i + 1], 16)
                if (d1 == -1 || d2 == -1) return null
                result[i / 2] = ((d1 shl 4) or d2).toByte()
            }
            return result
        }

        /**
         * Normalizes and parses a SHA-256 certificate fingerprint string into 32 raw bytes.
         *
         * Accepts:
         * - 64-character hex strings (case-insensitive)
         * - Base64 strings prefixed with `sha256/` (OkHttp CertificatePinner syntax)
         * - Raw 44-character Base64 strings
         *
         * @return 32-byte SHA-256 fingerprint, or null if the input format is invalid.
         */
        fun parseFingerprint(input: String): ByteArray? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null

            // 1. Try sha256/<base64>
            if (trimmed.startsWith("sha256/", ignoreCase = true)) {
                val b64 = trimmed.substring(7).trim()
                return try {
                    val bytes = java.util.Base64.getDecoder().decode(b64)
                    if (bytes.size == 32) bytes else null
                } catch (_: Exception) {
                    null
                }
            }

            // 2. Try 64-character hex
            if (trimmed.length == 64 && trimmed.all { Character.digit(it, 16) != -1 }) {
                return hexToBytes(trimmed)?.takeIf { it.size == 32 }
            }

            // 3. Try raw 44-character Base64 (standard for 32 bytes)
            if (trimmed.length in 43..44) {
                return try {
                    val bytes = java.util.Base64.getDecoder().decode(trimmed)
                    if (bytes.size == 32) bytes else null
                } catch (_: Exception) {
                    null
                }
            }

            return null
        }

        /**
         * Creates an [EncryptedSharedPreferences] instance backed by AES-256-GCM.
         * Falls back to standard private preferences if Keystore or encryption initialization fails.
         */
        @Suppress("DEPRECATION")
        fun createEncryptedSharedPreferences(context: Context): SharedPreferences {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                try {
                    // Delete corrupted encrypted file before fallback to prevent XML parse crashes
                    context.deleteSharedPreferences(PREFS_NAME)
                } catch (_: Exception) {}
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }
}
