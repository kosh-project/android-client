package project.kosh.feature.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import project.kosh.core.auth.AuthRepository
import project.kosh.core.auth.PasskeyAuthManager
import project.kosh.core.auth.PasskeyLoginResult
import project.kosh.core.auth.PasskeyRegisterResult
import project.kosh.core.crypto.Bip39Sas
import project.kosh.core.crypto.IdentityManager
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.DiscoveredServer
import project.kosh.core.network.MdnsDiscoveryManager
import project.kosh.core.network.PingResult
import project.kosh.core.network.ServerConfig
import project.kosh.core.network.ServerConfigurationStore
import project.kosh.core.network.ServerHealthPinger
import project.kosh.core.auth.DevicePairingManager
import project.kosh.core.auth.PairingState
import project.kosh.core.model.ActivePairingSession
import project.kosh.core.model.PairingQrPayload
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * State representing the status of an active server ping probe.
 */
sealed interface PingUiState {
    data object Idle : PingUiState
    data object Pinging : PingUiState
    data class Success(val latencyMs: Long, val statusCode: Int, val isTls: Boolean) : PingUiState
    data class Error(val message: String, val isTls: Boolean) : PingUiState
}

/**
 * ViewModel managing the business logic and UI state transitions for user authentication.
 *
 * Implements Unidirectional Data Flow (UDF) by exposing state through an immutable [StateFlow]
 * ([state]) while receiving user intents via [login], [register], and [loginWithPasskey].
 *
 * Directs password authentication through [AuthRepository.login] and [AuthRepository.register],
 * ensuring zero-knowledge Argon2id Key Encryption Key (KEK) derivation and AES-GCM vault envelope
 * wrapping/unwrapping executed safely on [Dispatchers.IO].
 *
 * @param authRepository Domain repository orchestrating PoW challenges and server communication.
 * @param identityManager Cryptographic service responsible for Argon2id derivations.
 * @param passkeyAuthManager FIDO2 / WebAuthn coordinator for biometric authentication.
 * @param serverConfigStore Hardware-backed storage for server connection topology.
 * @param mdnsDiscoveryManager Native mDNS manager for zero-config server discovery on local LAN.
 * @param serverHealthPinger Network service probing /health and measuring latency.
 * @param devicePairingManager E2EE ECDH device-to-device pairing manager.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val identityManager: IdentityManager,
    private val passkeyAuthManager: PasskeyAuthManager,
    private val serverConfigStore: ServerConfigurationStore,
    private val mdnsDiscoveryManager: MdnsDiscoveryManager,
    private val serverHealthPinger: ServerHealthPinger,
    private val devicePairingManager: DevicePairingManager
) : ViewModel() {

    /**
     * Coroutine dispatcher used for CPU-intensive cryptographic derivations.
     * Defaults to [Dispatchers.Default]; exposed as internal for test overrides.
     */
    internal var defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    private var pairingJob: Job? = null

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)

    /**
     * Observable stream of discrete authentication UI states.
     */
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private val _pingState = MutableStateFlow<PingUiState>(PingUiState.Idle)

    /**
     * Observable stream representing server connectivity probe and latency status.
     */
    val pingState: StateFlow<PingUiState> = _pingState.asStateFlow()

    /**
     * Observable stream of the active server connection configuration.
     */
    val serverConfig: StateFlow<ServerConfig> = serverConfigStore.serverConfig

    /**
     * Live cold stream of discovered Kosh servers on the local network via mDNS.
     */
    val discoveredServers: StateFlow<List<DiscoveredServer>> =
        mdnsDiscoveryManager.discoverServers()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    private val json = Json { ignoreUnknownKeys = true }

    private val _hasStoredCredentials = MutableStateFlow(
        passkeyAuthManager.hasEnrolledPasskeys() || authRepository.hasStoredCredentials()
    )

    /**
     * Observable flag indicating whether this device has an enrolled biometric passkey
     * or active session credentials persisted in hardware-backed KeyStore.
     */
    val hasStoredCredentials: StateFlow<Boolean> = _hasStoredCredentials.asStateFlow()

    fun refreshStoredCredentials() {
        _hasStoredCredentials.value =
            passkeyAuthManager.hasEnrolledPasskeys() || authRepository.hasStoredCredentials()
    }

    private val _pairingState = MutableStateFlow<PairingState?>(null)

    /**
     * Observable stream representing out-of-band ECDH device pairing lifecycle.
     */
    val pairingState: StateFlow<PairingState?> = _pairingState.asStateFlow()

    private val _activePairingSession = MutableStateFlow<ActivePairingSession?>(null)

    /**
     * Observable active pairing session containing mailbox ID, QR payload, and expiration.
     * Retained across state transitions (e.g. while awaiting fulfillment or expired)
     * so that the UI can continue to render and animate the QR code and pairing code.
     */
    val activePairingSession: StateFlow<ActivePairingSession?> = _activePairingSession.asStateFlow()

    private val _lastRegistrationTicket = MutableStateFlow<String?>(null)

    /**
     * Observable single-use registration ticket acquired from successful pairing.
     */
    val lastRegistrationTicket: StateFlow<String?> = _lastRegistrationTicket.asStateFlow()


    /**
     * Initiates the login authentication workflow.
     *
     * Validates input, sets state to [AuthUiState.Loading], and executes [AuthRepository.login]
     * with username and passphrase to derive credentials and unwrap the Master Vault Key.
     *
     * @param username Plaintext account username.
     * @param passphrase Plaintext account password.
     */
    fun login(username: String, passphrase: String) {
        val cleanUser = username.trim()
        if (cleanUser.isBlank() || passphrase.isBlank()) {
            _state.value = AuthUiState.Error("Username and passphrase cannot be blank.")
            return
        }

        viewModelScope.launch {
            _state.value = AuthUiState.Loading("Solving PoW challenge and unlocking vault...")

            try {
                when (val result = authRepository.login(cleanUser, passphrase)) {
                    is NetworkResult.Success -> {
                        _state.value = AuthUiState.Success(isNewRegistration = false)
                    }
                    is NetworkResult.Http -> {
                        _state.value = AuthUiState.Error(result.serverMessage)
                    }
                    is NetworkResult.Failure -> {
                        _state.value = AuthUiState.Error(result.userMessage)
                    }
                }
            } catch (e: Exception) {
                _state.value = AuthUiState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    /**
     * Initiates the new user registration workflow.
     *
     * Validates input, sets state to [AuthUiState.Loading], and executes [AuthRepository.register]
     * with username and passphrase to generate a fresh Master Vault Key and wrap it with KEK.
     *
     * @param username Plaintext account username.
     * @param passphrase Plaintext account password.
     */
    fun register(username: String, passphrase: String) {
        val cleanUser = username.trim()
        if (cleanUser.length < 3) {
            _state.value = AuthUiState.Error("Username must be at least 3 characters.")
            return
        }
        if (passphrase.length < 8) {
            _state.value = AuthUiState.Error("Passphrase must be at least 8 characters.")
            return
        }

        viewModelScope.launch {
            passkeyAuthManager.clearPasskeys()
            refreshStoredCredentials()
            _state.value = AuthUiState.Loading("Generating vault keys & creating account...")

            try {
                when (val result = authRepository.register(cleanUser, passphrase)) {
                    is NetworkResult.Success -> {
                        _state.value = AuthUiState.Loading("Authenticating session...")
                        when (val loginResult = authRepository.login(cleanUser, passphrase)) {
                            is NetworkResult.Success -> {
                                _state.value = AuthUiState.Success(isNewRegistration = true)
                            }
                            is NetworkResult.Http -> {
                                _state.value = AuthUiState.Error(loginResult.serverMessage)
                            }
                            is NetworkResult.Failure -> {
                                _state.value = AuthUiState.Error(loginResult.userMessage)
                            }
                        }
                    }
                    is NetworkResult.Http -> {
                        _state.value = AuthUiState.Error(result.serverMessage)
                    }
                    is NetworkResult.Failure -> {
                        _state.value = AuthUiState.Error(result.userMessage)
                    }
                }
            } catch (e: Exception) {
                _state.value = AuthUiState.Error("Unexpected error: ${e.message}")
            }
        }
    }

    /**
     * Executes discoverable FIDO2 / WebAuthn passkey authentication.
     *
     * Prompts the user for biometric authorization via Android CredentialManager.
     * If the vault key is already unlocked or cached, transitions directly to [AuthUiState.Success].
     * Otherwise requests one-time passphrase unlock via [AuthUiState.VaultUnlockRequired].
     *
     * @param activityContext Android Activity context required for the biometric prompt.
     * @param passphrase Optional master passphrase for automatic vault key unwrapping.
     */
    fun loginWithPasskey(activityContext: Context, passphrase: String? = null) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading("Awaiting biometric authorization...")
            try {
                when (val result = passkeyAuthManager.loginWithPasskey(activityContext, passphrase)) {
                    is PasskeyLoginResult.Success -> {
                        if (result.masterVaultKey != null || authRepository.isLoggedIn()) {
                            _state.value = AuthUiState.Success(isNewRegistration = false)
                        } else {
                            _state.value = AuthUiState.VaultUnlockRequired(
                                token = result.token,
                                wrappedVaultKey = result.wrappedVaultKey
                            )
                        }
                    }
                    is PasskeyLoginResult.Cancelled -> {
                        _state.value = AuthUiState.Idle
                    }
                    is PasskeyLoginResult.Error -> {
                        _state.value = AuthUiState.Error(result.message)
                    }
                }
            } catch (e: Exception) {
                _state.value = AuthUiState.Error("Passkey login failed: ${e.message}")
            }
        }
    }

    /**
     * Initiates the account registration workflow.
     *
     * 1. Generates a fresh 24-word BIP-39 Zero-Knowledge recovery phrase.
     * 2. Registers the new account on the server using PoW and the recovery phrase.
     * 3. Authenticates the initial session to obtain an active bearer token.
     * 4. Optionally attempts passkey enrollment via Android Credential Manager.
     *    Passkey failures (such as local network Digital Asset Links DAL restrictions) are non-blocking.
     * 5. Transitions to [AuthUiState.Success] carrying the 24 recovery words for mandatory cold storage backup.
     *
     * @param activityContext Android activity context required for the biometric dialog.
     * @param deviceNickname Optional user-defined device label.
     */
    fun registerWithPasskey(activityContext: Context, deviceNickname: String? = null) {
        viewModelScope.launch {
            passkeyAuthManager.clearPasskeys()
            refreshStoredCredentials()
            _state.value = AuthUiState.Loading("Generating Zero-Knowledge vault & recovery phrase...")
            try {
                val recoveryWords = Bip39Sas.generateRecoveryPhrase()
                val recoveryPhrase = recoveryWords.joinToString(" ")

                _state.value = AuthUiState.Loading("Solving PoW puzzle & creating account...")
                val regResult = authRepository.registerWithRecoveryPhrase(recoveryPhrase)
                when (regResult) {
                    is NetworkResult.Success -> {
                        _state.value = AuthUiState.Loading("Authenticating session...")
                        val loginResult = authRepository.loginWithRecoveryPhrase(recoveryPhrase)
                        when (loginResult) {
                            is NetworkResult.Success -> {
                                _state.value = AuthUiState.Success(
                                    isNewRegistration = true,
                                    recoveryWords = recoveryWords
                                )
                            }
                            is NetworkResult.Http -> {
                                _state.value = AuthUiState.Error(loginResult.serverMessage)
                            }
                            is NetworkResult.Failure -> {
                                _state.value = AuthUiState.Error(loginResult.userMessage)
                            }
                        }
                    }
                    is NetworkResult.Http -> {
                        _state.value = AuthUiState.Error(regResult.serverMessage)
                    }
                    is NetworkResult.Failure -> {
                        _state.value = AuthUiState.Error(regResult.userMessage)
                    }
                }
            } catch (e: Exception) {
                _state.value = AuthUiState.Error("Registration failed: ${e.message}")
            }
        }
    }

    /**
     * Restores account access and unlocks the Master Vault Key using the user's recovery phrase.
     *
     * @param recoveryPassphrase 24-word BIP-39 recovery phrase or master passphrase string.
     */
    fun recoverWithPassphrase(recoveryPassphrase: String) {
        val cleanPhrase = Bip39Sas.normalizePhrase(recoveryPassphrase)
        if (cleanPhrase.isBlank()) {
            _state.value = AuthUiState.Error("Recovery passphrase cannot be blank.")
            return
        }

        viewModelScope.launch {
            passkeyAuthManager.clearPasskeys()
            refreshStoredCredentials()
            _state.value = AuthUiState.Loading("Verifying recovery phrase & unlocking vault...")
            try {
                when (val result = authRepository.loginWithRecoveryPhrase(cleanPhrase)) {
                    is NetworkResult.Success -> {
                        _state.value = AuthUiState.Success(isNewRegistration = false)
                    }
                    is NetworkResult.Http -> {
                        _state.value = AuthUiState.Error(result.serverMessage)
                    }
                    is NetworkResult.Failure -> {
                        _state.value = AuthUiState.Error(result.userMessage)
                    }
                }
            } catch (e: Exception) {
                _state.value = AuthUiState.Error("Recovery failed: ${e.message}")
            }
        }
    }

    /**
     * Unwraps the Master Vault Key when authenticated via passkey on an uninitialized device.
     *
     * @param wrappedVaultKey Hex-encoded wrapped vault envelope.
     * @param passphrase User master passphrase.
     */
    fun unlockVault(wrappedVaultKey: String, passphrase: String) {
        val currentToken = (_state.value as? AuthUiState.VaultUnlockRequired)?.token.orEmpty()
        if (passphrase.isBlank()) {
            _state.value = AuthUiState.VaultUnlockRequired(
                token = currentToken,
                wrappedVaultKey = wrappedVaultKey,
                errorMessage = "Passphrase cannot be blank."
            )
            return
        }

        viewModelScope.launch {
            _state.value = AuthUiState.VaultUnlockRequired(
                token = currentToken,
                wrappedVaultKey = wrappedVaultKey,
                errorMessage = null,
                isUnlocking = true
            )
            try {
                val unlockedKey = withContext(defaultDispatcher) {
                    passkeyAuthManager.unlockVaultWithPassphrase(wrappedVaultKey, passphrase)
                }
                if (unlockedKey != null) {
                    _state.value = AuthUiState.Success(isNewRegistration = false)
                } else {
                    _state.value = AuthUiState.VaultUnlockRequired(
                        token = currentToken,
                        wrappedVaultKey = wrappedVaultKey,
                        errorMessage = "Invalid passphrase. Could not unwrap vault key.",
                        isUnlocking = false
                    )
                }
            } catch (e: Exception) {
                _state.value = AuthUiState.VaultUnlockRequired(
                    token = currentToken,
                    wrappedVaultKey = wrappedVaultKey,
                    errorMessage = "Vault unlock error: ${e.message}",
                    isUnlocking = false
                )
            }
        }
    }

    /**
     * Cancels a pending vault key unlock ceremony and clears any incomplete session token.
     */
    fun cancelVaultUnlock() {
        pairingJob?.cancel()
        pairingJob = null
        val currentPairing = _pairingState.value
        if (currentPairing is PairingState.Success) {
            currentPairing.vaultKey.close()
        }
        _pairingState.value = null
        if (_state.value is AuthUiState.VaultUnlockRequired) {
            authRepository.logout()
        }
        _state.value = AuthUiState.Idle
    }

    /**
     * Persists updated server host, port, and TLS connection parameters.
     */
    fun updateServerConfig(host: String, port: Int, useTls: Boolean) {
        serverConfigStore.updateServer(host.trim(), port, useTls)
    }

    /**
     * Resets server connection configuration to canonical defaults.
     */
    fun resetServerConfigToDefault() {
        serverConfigStore.updateServer(
            ServerConfig.DEFAULT_HOST,
            ServerConfig.DEFAULT_PORT,
            ServerConfig.DEFAULT_USE_TLS
        )
    }

    /**
     * Probes the server at `/health` and measures round-trip latency.
     *
     * @param host Target host name or IP address.
     * @param port Target TCP port.
     * @param useTls Whether to test using HTTPS or HTTP.
     */
    fun pingServer(host: String, port: Int, useTls: Boolean) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank() || port !in 1..65535) {
            _pingState.value = PingUiState.Error("Invalid host or port", useTls)
            return
        }

        viewModelScope.launch {
            _pingState.value = PingUiState.Pinging
            when (val result = serverHealthPinger.ping(cleanHost, port, useTls)) {
                is PingResult.Success -> {
                    _pingState.value = PingUiState.Success(
                        latencyMs = result.latencyMs,
                        statusCode = result.statusCode,
                        isTls = result.isTls
                    )
                }
                is PingResult.Failure -> {
                    _pingState.value = PingUiState.Error(
                        message = result.error,
                        isTls = result.isTls
                    )
                }
            }
        }
    }

    /**
     * Resets the active ping probe state back to [PingUiState.Idle].
     */
    fun resetPingState() {
        _pingState.value = PingUiState.Idle
    }

    /**
     * Clears error or loading state back to [AuthUiState.Idle].
     */
    fun resetState() {
        _state.value = AuthUiState.Idle
    }

    /**
     * Initiates out-of-band device pairing on the authentication screen (Flow A).
     *
     * As an unauthenticated client, this device acts as a receiver: it solves the Hashcash PoW puzzle,
     * opens an ephemeral mailbox on the server, generates an X25519 keypair, and displays its pairing
     * QR code awaiting fulfillment from an authorized device holding the Master Vault Key.
     */
    fun startDevicePairing() {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            _pairingState.value = null
            _activePairingSession.value = null
            if (_state.value !is AuthUiState.VaultUnlockRequired) {
                _state.value = AuthUiState.Idle
            }
            try {
                // Flow A: Unauthenticated receiver displays QR code to let this device sign in via an authorized device
                devicePairingManager.startReceiverFlow().collect { pState ->
                    if (pState is PairingState.Ready) {
                        _activePairingSession.value = ActivePairingSession(
                            mailboxId = pState.mailboxId,
                            qrPayloadJson = pState.qrPayloadJson,
                            expiresInSeconds = pState.expiresInSeconds
                        )
                    } else if (pState is PairingState.AwaitingJoin) {
                        _activePairingSession.value = ActivePairingSession(
                            mailboxId = pState.mailboxId,
                            qrPayloadJson = pState.qrPayloadJson,
                            expiresInSeconds = pState.expiresInSeconds
                        )
                    }
                    _pairingState.value = pState
                    if (pState is PairingState.Success) {
                        pState.registrationTicket?.let { ticket ->
                            _lastRegistrationTicket.value = ticket
                        }
                        refreshStoredCredentials()
                        val isVaultUnlock = _state.value is AuthUiState.VaultUnlockRequired
                        val isNewDevice = !isVaultUnlock && !pState.registrationTicket.isNullOrBlank()
                        _state.value = AuthUiState.Success(isNewRegistration = isNewDevice)
                    } else if (pState is PairingState.Error) {
                        val pendingUnlock = _state.value as? AuthUiState.VaultUnlockRequired
                        if (pendingUnlock != null) {
                            _state.value = pendingUnlock.copy(errorMessage = "Pairing error: ${pState.message}")
                        } else {
                            _state.value = AuthUiState.Error("Pairing error: ${pState.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                _pairingState.value = PairingState.Error("Device pairing failed: ${e.message}", e)
                val pendingUnlock = _state.value as? AuthUiState.VaultUnlockRequired
                if (pendingUnlock != null) {
                    _state.value = pendingUnlock.copy(errorMessage = "Pairing failed: ${e.message}")
                } else {
                    _state.value = AuthUiState.Error("Pairing failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Pairs with a peer device by parsing a scanned [PairingQrPayload] JSON string.
     * If this device possesses the Master Vault Key, it fulfills the peer receiver's session (Flow A).
     * If this device lacks the Master Vault Key, it joins the host's session (Flow B).
     */
    fun linkWithScannedQr(qrJson: String) {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            val wasVaultUnlock = _state.value is AuthUiState.VaultUnlockRequired
            val pendingUnlock = _state.value as? AuthUiState.VaultUnlockRequired
            _state.value = AuthUiState.Loading("Processing pairing code...")
            try {
                val payload = json.decodeFromString<PairingQrPayload>(qrJson)
                val payloadHost = payload.host

                suspend fun updateServerFromPayload() {
                    if (!payloadHost.isNullOrBlank()) {
                        val fingerprintBytes = payload.fingerprint?.let {
                            ServerConfigurationStore.parseFingerprint(it)
                        } ?: serverConfigStore.getServerConfig().pinnedCertFingerprintSha256

                        serverConfigStore.updateServer(
                            host = payloadHost,
                            port = payload.port ?: 23342,
                            useTls = payload.fingerprint != null || serverConfigStore.isTlsEnabled(),
                            pinnedCertFingerprintSha256 = fingerprintBytes
                        )
                    }
                }

                if (authRepository.hasVaultKey()) {
                    // Flow A: Sender fulfills receiver mailbox
                    val originalConfig = serverConfigStore.getServerConfig()
                    val rxPubKey = payload.publicKeyHex.orEmpty()
                    suspend fun tryFulfill(): NetworkResult<Unit> {
                        return if (rxPubKey.isNotBlank()) {
                            devicePairingManager.fulfillSenderSession(
                                mailboxId = payload.mailboxId,
                                receiverPublicKeyHex = rxPubKey,
                                token = payload.token
                            )
                        } else {
                            devicePairingManager.fulfillSenderWithCode(
                                mailboxId = payload.mailboxId,
                                token = payload.token
                            )
                        }
                    }

                    var fulfillResult = tryFulfill()
                    // Fallback to QR server only if not found on current server
                    if (fulfillResult is NetworkResult.Failure && !payloadHost.isNullOrBlank()) {
                        if (payloadHost != originalConfig.host || (payload.port != null && payload.port != originalConfig.port)) {
                            updateServerFromPayload()
                            fulfillResult = tryFulfill()
                            if (fulfillResult is NetworkResult.Failure) {
                                serverConfigStore.updateServer(
                                    host = originalConfig.host,
                                    port = originalConfig.port,
                                    useTls = originalConfig.useTls,
                                    pinnedCertFingerprintSha256 = originalConfig.pinnedCertFingerprintSha256
                                )
                            }
                        }
                    }

                    when (fulfillResult) {
                        is NetworkResult.Success -> {
                            _state.value = AuthUiState.Success(isNewRegistration = false)
                        }
                        is NetworkResult.Failure -> {
                            _state.value = AuthUiState.Error("Failed to fulfill pairing: ${fulfillResult.userMessage}")
                        }
                    }
                } else {
                    // Flow B: Receiver joins host mailbox
                    _pairingState.value = PairingState.AwaitingFulfillment
                    devicePairingManager.startJoinFlow(
                        mailboxId = payload.mailboxId,
                        host = payload.host,
                        port = payload.port,
                        fingerprint = payload.fingerprint
                    ).collect { pState ->
                        _pairingState.value = pState
                        if (pState is PairingState.Success) {
                            pState.registrationTicket?.let { ticket ->
                                _lastRegistrationTicket.value = ticket
                            }
                            refreshStoredCredentials()
                            val isNewDevice = !wasVaultUnlock && !pState.registrationTicket.isNullOrBlank()
                            _state.value = AuthUiState.Success(isNewRegistration = isNewDevice)
                        } else if (pState is PairingState.Error) {
                            if (wasVaultUnlock && pendingUnlock != null) {
                                _state.value = pendingUnlock.copy(errorMessage = "Pairing error: ${pState.message}")
                            } else {
                                _state.value = AuthUiState.Error("Pairing error: ${pState.message}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                val plainCode = qrJson.trim().replace("-", "").replace(" ", "").lowercase()
                if (plainCode.length == 8) {
                    linkWithPairingCode(plainCode)
                } else {
                    if (wasVaultUnlock && pendingUnlock != null) {
                        _state.value = pendingUnlock.copy(errorMessage = "Invalid pairing QR code: ${e.message}")
                    } else {
                        _state.value = AuthUiState.Error("Invalid pairing QR code: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Pairs with a peer device using an 8-character Crockford Base32 pairing code.
     * If this device already possesses the Master Vault Key, it fulfills the peer's receiver session.
     * If this device lacks the Master Vault Key, it joins the host's session (Flow B).
     */
    fun linkWithPairingCode(code: String) {
        val cleanCode = code.trim().replace("-", "").replace(" ", "").lowercase()
        if (cleanCode.length != 8) {
            val pendingUnlock = _state.value as? AuthUiState.VaultUnlockRequired
            if (pendingUnlock != null) {
                _state.value = pendingUnlock.copy(errorMessage = "Pairing code must be 8 characters")
            } else {
                _state.value = AuthUiState.Error("Pairing code must be 8 characters")
            }
            return
        }

        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            val wasVaultUnlock = _state.value is AuthUiState.VaultUnlockRequired
            val pendingUnlock = _state.value as? AuthUiState.VaultUnlockRequired
            if (authRepository.hasVaultKey()) {
                _state.value = AuthUiState.Loading("Fulfilling pairing session...")
                when (val result = devicePairingManager.fulfillSenderWithCode(cleanCode)) {
                    is NetworkResult.Success -> {
                        _state.value = AuthUiState.Success(isNewRegistration = false)
                    }
                    is NetworkResult.Failure -> {
                        _state.value = AuthUiState.Error("Failed to fulfill pairing: ${result.userMessage}")
                    }
                }
            } else {
                _pairingState.value = PairingState.AwaitingFulfillment
                devicePairingManager.startJoinFlow(mailboxId = cleanCode).collect { pState ->
                    _pairingState.value = pState
                    if (pState is PairingState.Success) {
                        pState.registrationTicket?.let { ticket ->
                            _lastRegistrationTicket.value = ticket
                        }
                        refreshStoredCredentials()
                        val isNewDevice = !wasVaultUnlock && !pState.registrationTicket.isNullOrBlank()
                        _state.value = AuthUiState.Success(isNewRegistration = isNewDevice)
                    } else if (pState is PairingState.Error) {
                        if (wasVaultUnlock && pendingUnlock != null) {
                            _state.value = pendingUnlock.copy(errorMessage = "Pairing error: ${pState.message}")
                        } else {
                            _state.value = AuthUiState.Error("Pairing error: ${pState.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Registers a new biometric passkey credential using a one-time pairing registration ticket.
     *
     * @param activityContext Android Activity context for biometric dialog.
     * @param registrationTicket Single-use hex-encoded registration ticket minted during pairing.
     * @param deviceNickname Optional user-defined nickname for this device.
     */
    fun enrollPasskeyWithTicket(
        activityContext: Context,
        registrationTicket: String,
        deviceNickname: String? = null
    ) {
        viewModelScope.launch {
            _state.value = AuthUiState.Loading("Enrolling passkey with Android Credential Manager...")
            try {
                val nickname = deviceNickname ?: "Android Device"
                val result = passkeyAuthManager.registerPasskeyWithTicket(
                    activityContext = activityContext,
                    registrationTicket = registrationTicket,
                    deviceNickname = nickname
                )
                when (result) {
                    is PasskeyRegisterResult.Success -> {
                        when (val loginResult = passkeyAuthManager.loginWithPasskey(activityContext)) {
                            is PasskeyLoginResult.Success -> {
                                refreshStoredCredentials()
                                _state.value = AuthUiState.Success(isNewRegistration = false)
                            }
                            is PasskeyLoginResult.Cancelled -> {
                                _state.value = AuthUiState.Error("Passkey login cancelled")
                            }
                            is PasskeyLoginResult.Error -> {
                                _state.value = AuthUiState.Error("Passkey login failed: ${loginResult.message}")
                            }
                        }
                    }
                    is PasskeyRegisterResult.Cancelled -> {
                        _state.value = AuthUiState.Error("Passkey enrollment cancelled")
                    }
                    is PasskeyRegisterResult.Error -> {
                        _state.value = AuthUiState.Error("Passkey enrollment failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                _state.value = AuthUiState.Error("Passkey enrollment failed: ${e.message}")
            }
        }
    }

    /**
     * Cancels an ongoing device pairing session and wipes any active key in memory.
     */
    fun cancelDevicePairing() {
        pairingJob?.cancel()
        pairingJob = null
        val currentPairing = _pairingState.value
        if (currentPairing is PairingState.Success) {
            currentPairing.vaultKey.close()
        }
        _pairingState.value = null
        _activePairingSession.value = null
        if (_state.value !is AuthUiState.VaultUnlockRequired) {
            _state.value = AuthUiState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        pairingJob?.cancel()
        pairingJob = null
        val currentPairing = _pairingState.value
        if (currentPairing is PairingState.Success) {
            currentPairing.vaultKey.close()
        }
    }
}

/**
 * Snapshot of an active ephemeral pairing session for display and animated blur lifecycle.
 */
typealias ActivePairingSession = project.kosh.core.model.ActivePairingSession

