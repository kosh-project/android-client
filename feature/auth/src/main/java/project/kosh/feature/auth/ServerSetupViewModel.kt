package project.kosh.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import project.kosh.core.crypto.Bip39Sas
import project.kosh.core.network.CertificateProbeResult
import project.kosh.core.network.CertificateProber
import project.kosh.core.network.DiscoveredServer
import project.kosh.core.network.MdnsDiscoveryManager
import project.kosh.core.network.ServerConfig
import project.kosh.core.network.ServerConfigurationStore
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Discrete progression steps for server discovery and TOFU certificate verification.
 */
sealed interface ServerSetupStage {
    /**
     * Step 1: User locates the server via mDNS discovery or manual configuration.
     */
    data object Locating : ServerSetupStage

    /**
     * Active probing in flight to GET /health and capture the leaf X.509 certificate.
     */
    data class Probing(val host: String, val port: Int, val isTls: Boolean) : ServerSetupStage

    /**
     * Step 2: Server responded. Display certificate fingerprint and SAS words for verification.
     */
    data class Verification(val result: CertificateProbeResult) : ServerSetupStage
}

/**
 * UI state for the server setup workflow.
 */
data class ServerSetupUiState(
    val stage: ServerSetupStage = ServerSetupStage.Locating,
    val probeError: String? = null,
    val showMismatchDialog: Boolean = false,
    val mismatchScannedText: String? = null,
    val isVerified: Boolean = false
)

/**
 * ViewModel orchestrating initial server discovery, connectivity probing,
 * and Trust-On-First-Use (TOFU) certificate fingerprint verification.
 *
 * @param certificateProber Network service executing unpinned handshakes and capturing certificates.
 * @param serverConfigStore Hardware-backed storage for server topology and pinned fingerprints.
 * @param mdnsDiscoveryManager Native mDNS manager for zero-config server discovery on local LAN.
 */
@HiltViewModel
class ServerSetupViewModel @Inject constructor(
    private val certificateProber: CertificateProber,
    private val serverConfigStore: ServerConfigurationStore,
    private val mdnsDiscoveryManager: MdnsDiscoveryManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerSetupUiState())
    val uiState: StateFlow<ServerSetupUiState> = _uiState.asStateFlow()

    /**
     * Real-time stream of the current persisted server configuration.
     */
    val serverConfig: StateFlow<ServerConfig> = serverConfigStore.serverConfig

    /**
     * Live stream of discovered Kosh storage nodes on the local Wi-Fi network.
     */
    val discoveredServers: StateFlow<List<DiscoveredServer>> =
        mdnsDiscoveryManager.discoverServers()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    /**
     * Initiates probing against the chosen server endpoint.
     *
     * @param host Target host name, IPv4/IPv6 address, or mDNS domain (e.g. "kosh.local").
     * @param port Target TCP port.
     * @param useTls Whether to negotiate TLS (HTTPS) or use plaintext HTTP.
     */
    fun selectAndProbeServer(host: String, port: Int, useTls: Boolean) {
        val cleanHost = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')

        if (cleanHost.isBlank() || port !in 1..65535) {
            _uiState.value = _uiState.value.copy(
                stage = ServerSetupStage.Locating,
                probeError = "Please enter a valid host and port (1 - 65535)."
            )
            return
        }

        // Pre-register matched discovered server IP with KoshDns to ensure .local lookups resolve
        val matchedServer = discoveredServers.value.firstOrNull {
            it.domain.equals(cleanHost, ignoreCase = true) ||
            it.name.equals(cleanHost, ignoreCase = true) ||
            "${it.name}.local".equals(cleanHost, ignoreCase = true) ||
            it.host.equals(cleanHost, ignoreCase = true)
        }
        if (matchedServer != null) {
            serverConfigStore.registerHostIp(cleanHost, matchedServer.host)
            serverConfigStore.registerHostIp(matchedServer.domain, matchedServer.host)
            serverConfigStore.registerHostIp(matchedServer.name, matchedServer.host)
            val serverHostname = matchedServer.hostname
            if (!serverHostname.isNullOrBlank()) {
                serverConfigStore.registerHostIp(serverHostname, matchedServer.host)
            }
        }

        _uiState.value = ServerSetupUiState(
            stage = ServerSetupStage.Probing(cleanHost, port, useTls),
            probeError = null
        )

        viewModelScope.launch {
            certificateProber.probeServer(cleanHost, port, useTls)
                .onSuccess { probeResult ->
                    _uiState.value = ServerSetupUiState(
                        stage = ServerSetupStage.Verification(probeResult),
                        probeError = null
                    )
                }
                .onFailure { error ->
                    val errorMsg = when (error) {
                        is java.net.ConnectException -> "Connection refused at $cleanHost:$port. Verify the server is running."
                        is java.net.SocketTimeoutException -> "Connection timed out connecting to $cleanHost:$port."
                        is java.net.UnknownHostException -> "Host unresolvable: $cleanHost. Ensure your device is connected to the same Wi-Fi network."
                        is javax.net.ssl.SSLException -> "TLS handshake failure: ${error.message ?: error.javaClass.simpleName}"
                        else -> error.message ?: "Failed to connect to server."
                    }
                    _uiState.value = ServerSetupUiState(
                        stage = ServerSetupStage.Locating,
                        probeError = errorMsg
                    )
                }
        }
    }

    /**
     * Verifies scanned QR code payload against the active probed certificate fingerprint.
     *
     * Evaluates binary raw bytes, 64-character hex strings, Base64 strings, ISO-8859-1 binary
     * strings, and space-separated 12 BIP-39 SAS words.
     *
     * @param rawBytes Binary byte array from the scanner, if available.
     * @param rawValue String content from the scanner, if available.
     */
    fun verifyScannedFingerprint(rawBytes: ByteArray?, rawValue: String?) {
        val currentStage = _uiState.value.stage as? ServerSetupStage.Verification ?: return
        val actual = currentStage.result.fingerprintBytes
        val actualHex = currentStage.result.fingerprintHex
        val actualSas = currentStage.result.sasWords

        if (actual.size != 32) return

        var isMatch = false

        // 1. Direct binary comparison if raw bytes were decoded (exact 32-byte or framed window)
        if (rawBytes != null && rawBytes.size >= 32) {
            if (rawBytes.size == 32 && MessageDigest.isEqual(actual, rawBytes)) {
                isMatch = true
            } else {
                for (i in 0..(rawBytes.size - 32)) {
                    val window = rawBytes.copyOfRange(i, i + 32)
                    if (MessageDigest.isEqual(actual, window)) {
                        isMatch = true
                        break
                    }
                }
            }
        }

        // 1b. If rawBytes is a 34+ byte QR codeword buffer with 12-bit shifted byte mode
        if (!isMatch && rawBytes != null && rawBytes.size >= 34) {
            val mode = (rawBytes[0].toInt() and 0xF0) ushr 4
            if (mode == 4) {
                val len = ((rawBytes[0].toInt() and 0x0F) shl 4) or ((rawBytes[1].toInt() and 0xF0) ushr 4)
                if (len >= 32 && rawBytes.size >= 2 + len) {
                    val unpacked = ByteArray(len) { i ->
                        val high = (rawBytes[1 + i].toInt() and 0x0F) shl 4
                        val low = (rawBytes[2 + i].toInt() and 0xF0) ushr 4
                        (high or low).toByte()
                    }
                    if (unpacked.size == 32 && MessageDigest.isEqual(actual, unpacked)) {
                        isMatch = true
                    } else {
                        for (i in 0..(unpacked.size - 32)) {
                            val window = unpacked.copyOfRange(i, i + 32)
                            if (MessageDigest.isEqual(actual, window)) {
                                isMatch = true
                                break
                            }
                        }
                    }
                }
            }
        }

        // 2. String comparison if text was decoded (Hex, Base64, ISO-8859-1 binary, or SAS words)
        if (!isMatch && !rawValue.isNullOrBlank()) {
            val trimmed = rawValue.trim()

            // 2a. Parsed fingerprint from hex or base64 (e.g. 64-char hex, sha256/<base64>)
            val parsed = ServerConfigurationStore.parseFingerprint(trimmed)
            if (parsed != null && MessageDigest.isEqual(actual, parsed)) {
                isMatch = true
            }

            // 2b. ISO-8859-1 binary decoding (standard for raw binary mode in QR scanners)
            val isoBytes = rawValue.toByteArray(Charsets.ISO_8859_1)
            if (isoBytes.size >= 32) {
                for (i in 0..(isoBytes.size - 32)) {
                    val window = isoBytes.copyOfRange(i, i + 32)
                    if (MessageDigest.isEqual(actual, window)) {
                        isMatch = true
                        break
                    }
                }
            }

            // 2c. 12 BIP-39 SAS words match
            val words = trimmed.lowercase().split("\\s+".toRegex()).filter { it.isNotEmpty() }
            if (words.size == 12) {
                var wordsMatch = true
                for (w in 0 until 12) {
                    if (words[w] != actualSas[w].lowercase()) {
                        wordsMatch = false
                    }
                }
                if (wordsMatch) {
                    isMatch = true
                }
            }
        }

        if (isMatch) {
            confirmVerification()
        } else {
            val previewText = when {
                !rawValue.isNullOrBlank() && rawValue.all { it.code in 32..126 || it == '\n' || it == '\r' } -> rawValue.trim()
                rawBytes != null -> ServerConfigurationStore.bytesToHex(rawBytes)
                !rawValue.isNullOrBlank() -> ServerConfigurationStore.bytesToHex(rawValue.toByteArray(Charsets.ISO_8859_1))
                else -> "Unknown code format"
            }
            _uiState.value = _uiState.value.copy(
                showMismatchDialog = true,
                mismatchScannedText = previewText
            )
        }
    }

    /**
     * Confirms certificate authenticity and persists the pinned fingerprint.
     */
    fun confirmVerification() {
        val currentStage = _uiState.value.stage as? ServerSetupStage.Verification ?: return
        val probe = currentStage.result

        serverConfigStore.updateServer(
            host = probe.host,
            port = probe.port,
            useTls = probe.isTls,
            pinnedCertFingerprintSha256 = if (probe.isTls) probe.fingerprintBytes else null
        )
        _uiState.value = _uiState.value.copy(
            isVerified = true,
            showMismatchDialog = false
        )
    }

    /**
     * Returns to Step 1 (Locating) so the user can select or input a different server.
     */
    fun backToLocating() {
        _uiState.value = ServerSetupUiState(
            stage = ServerSetupStage.Locating,
            probeError = null,
            showMismatchDialog = false
        )
    }

    /**
     * Dismisses the mismatch warning dialog so the user can retry scanning.
     */
    fun dismissMismatchDialog() {
        _uiState.value = _uiState.value.copy(showMismatchDialog = false)
    }

    /**
     * Resets any active probe error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(probeError = null)
    }
}
