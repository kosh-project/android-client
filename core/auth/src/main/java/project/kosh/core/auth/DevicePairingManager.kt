package project.kosh.core.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import project.kosh.core.crypto.EcdhPairingCrypto
import project.kosh.core.crypto.HashcashSolver
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.model.MailboxPollState
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PairingQrPayload
import project.kosh.core.network.AuthClient
import project.kosh.core.network.MailboxClient
import project.kosh.core.network.ServerConfigurationStore
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain-level pairing state emitted during the device-to-device pairing lifecycle.
 */
sealed interface PairingState {
    /**
     * Mailbox allocated, ephemeral X25519 keys generated, and ready for sender to scan QR / input code (Flow A).
     *
     * @property mailboxId 8-character Crockford Base32 mailbox code.
     * @property qrPayloadJson Serialized [PairingQrPayload] suitable for rendering as a QR code.
     * @property expiresInSeconds Server-enforced TTL in seconds.
     */
    data class Ready(
        val mailboxId: String,
        val qrPayloadJson: String,
        val expiresInSeconds: Long
    ) : PairingState

    /**
     * Mailbox offered by sender, awaiting receiver join (Flow B).
     *
     * @property mailboxId 8-character Crockford Base32 mailbox code.
     * @property qrPayloadJson Serialized [PairingQrPayload] suitable for rendering as a QR code.
     * @property expiresInSeconds Server-enforced TTL in seconds.
     */
    data class AwaitingJoin(
        val mailboxId: String,
        val qrPayloadJson: String,
        val expiresInSeconds: Long
    ) : PairingState

    /**
     * Mailbox is waiting for the sender device to deposit the encrypted payload.
     */
    data object AwaitingFulfillment : PairingState

    /**
     * Successfully received, decrypted, and authenticated the transferred [MasterVaultKey] and optional [registrationTicket].
     *
     * @property vaultKey Decrypted [MasterVaultKey] stored in hardware KeyStore.
     * @property registrationTicket Optional single-use registration ticket for immediate passkey enrollment.
     */
    data class Success(
        val vaultKey: MasterVaultKey,
        val registrationTicket: String? = null
    ) : PairingState

    /**
     * Pairing failed due to timeout, network failure, or cryptographic integrity check failure.
     */
    data class Error(val message: String, val cause: Throwable? = null) : PairingState
}

/**
 * High-level coordinator managing End-to-End Encrypted (E2EE) device-to-device pairing.
 *
 * Implements bidirectional pairing UX flows:
 * - Flow A: Unauthenticated receiver device displays QR code, solves Hashcash PoW, opens mailbox, polls pickup,
 *           and receives compound { registration_ticket, MasterVaultKey } bundle.
 * - Flow B: Authenticated sender device displays QR code, receiver joins with PoW, sender fulfills with ticket + vault key.
 */
@Singleton
class DevicePairingManager @Inject constructor(
    private val mailboxClient: MailboxClient,
    private val authClient: AuthClient,
    private val hashcashSolver: HashcashSolver,
    private val sessionManager: SessionManager,
    private val serverConfigStore: ServerConfigurationStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    // ==========================================
    // Flow A: Receiver Displays QR Code
    // ==========================================

    /**
     * Initiates an onboarding pairing receiver flow on this device (Flow A: Receiver displays QR code).
     *
     * Completely unauthenticated: operates without pre-existing session tokens or passwords.
     * 1. Fetches a stateless Hashcash Proof-of-Work challenge from `/api/auth/challenge?action=register`.
     * 2. Solves the 16-bit leading zero puzzle on `Dispatchers.Default`.
     * 3. Generates an ephemeral X25519 keypair.
     * 4. Opens an ephemeral mailbox via `POST /api/auth/mailbox/open`.
     * 5. Emits [PairingState.Ready] containing [PairingQrPayload] for QR display.
     * 6. Polls the mailbox via `GET /api/auth/mailbox/{id}/pickup` with polling token.
     * 7. Decrypts compound pairing payload, extracts [MasterVaultKey] and registration ticket.
     * 8. Persists [MasterVaultKey] to KeyStore and emits [PairingState.Success].
     *
     * @param timeoutMillis Maximum polling duration in milliseconds (default 300 seconds / 5 minutes).
     * @return Cold [Flow] emitting progressive [PairingState] transitions.
     */
    fun startReceiverFlow(
        timeoutMillis: Long = 300_000L
    ): Flow<PairingState> = flow {
        // Step 1: Request PoW challenge
        val challengeResult = authClient.fetchChallenge("register")
        if (challengeResult !is NetworkResult.Success) {
            val failure = challengeResult as NetworkResult.Failure
            emit(PairingState.Error("Failed to fetch Proof-of-Work challenge: ${failure.userMessage}"))
            return@flow
        }

        // Step 2: Solve Hashcash puzzle
        val ephemeralIdentityHash = generateEphemeralIdentityHash()
        val powHeader = try {
            hashcashSolver.solve(ephemeralIdentityHash, challengeResult.data.challenge)
        } catch (e: Exception) {
            emit(PairingState.Error("Proof-of-Work solver failed: ${e.message}", e))
            return@flow
        }

        // Step 3: Generate ephemeral X25519 keypair
        val rxKeyPair = EcdhPairingCrypto.generateKeyPair()

        try {
            // Step 4: Open mailbox on server
            val openResult = mailboxClient.openMailbox(
                pubTempHex = EcdhPairingCrypto.bytesToHex(rxKeyPair.publicKey),
                hashcashHeader = powHeader
            )
            if (openResult !is NetworkResult.Success) {
                val failure = openResult as NetworkResult.Failure
                emit(PairingState.Error("Failed to open pairing mailbox: ${failure.userMessage}"))
                return@flow
            }

            val openData = openResult.data
            val serverConfig = serverConfigStore.getServerConfig()
            val qrPayload = PairingQrPayload(
                mailboxId = openData.mailboxId,
                publicKeyHex = EcdhPairingCrypto.bytesToHex(rxKeyPair.publicKey),
                host = serverConfig.host,
                port = serverConfig.port,
                fingerprint = serverConfig.fingerprintHex,
                mode = "receiver"
            )
            val qrJson = json.encodeToString(PairingQrPayload.serializer(), qrPayload)

            // Step 5: Emit Ready with QR payload
            emit(
                PairingState.Ready(
                    mailboxId = openData.mailboxId,
                    qrPayloadJson = qrJson,
                    expiresInSeconds = openData.expiresIn
                )
            )

            // Step 6: Poll pickup with polling token
            mailboxClient.pollPickup(
                mailboxId = openData.mailboxId,
                pollingToken = openData.pollingToken,
                timeoutMillis = timeoutMillis,
                pollIntervalMillis = 1_500L
            ).collect { pollState ->
                when (pollState) {
                    is MailboxPollState.Pending -> {
                        emit(PairingState.AwaitingFulfillment)
                    }
                    is MailboxPollState.Fulfilled -> {
                        var bundle: project.kosh.core.crypto.PairingBundle? = null
                        try {
                            bundle = EcdhPairingCrypto.decryptCompoundPayload(
                                payloadHex = pollState.payload,
                                receiverPrivateKey = rxKeyPair.privateKey
                            )
                            sessionManager.saveVaultKey(bundle.vaultKey)
                            if (bundle.registrationTicket.isNotBlank()) {
                                sessionManager.saveRegistrationTicket(bundle.registrationTicket)
                            }
                            emit(
                                PairingState.Success(
                                    vaultKey = bundle.vaultKey,
                                    registrationTicket = bundle.registrationTicket.ifEmpty { null }
                                )
                            )
                        } catch (e: Exception) {
                            bundle?.close()
                            emit(PairingState.Error("Failed to decrypt transferred pairing bundle: ${e.message}", e))
                        }
                    }
                    is MailboxPollState.Failed -> {
                        emit(PairingState.Error(pollState.message, pollState.cause))
                    }
                }
            }
        } finally {
            rxKeyPair.close()
        }
    }

    /**
     * Executes sender fulfillment: mints a registration ticket and transfers the active [MasterVaultKey] to the receiver.
     *
     * 1. Resolves the active [MasterVaultKey] from [SessionManager] (or accepts [vaultKeyOverride]).
     * 2. Mints a 300-second single-use registration ticket via `POST /api/v1/devices/link-ticket`.
     * 3. Performs X25519 ECDH + HKDF key agreement with [receiverPublicKeyHex].
     * 4. AES-256-GCM encrypts `{ registration_ticket, MasterVaultKey }` into a 248-hex compound envelope.
     * 5. Submits the envelope to `POST /api/v1/devices/mailbox/{mailboxId}/fulfill`.
     *
     * @param mailboxId 8-character Crockford Base32 mailbox code.
     * @param receiverPublicKeyHex 64-hex-character string representing receiver's 32-byte public key.
     * @param token Optional explicit session token.
     * @param vaultKeyOverride Optional vault key override if transferring an explicitly unlocked key.
     * @return [NetworkResult.Success] on completion or [NetworkResult.Failure].
     */
    suspend fun fulfillSenderSession(
        mailboxId: String,
        receiverPublicKeyHex: String,
        token: String? = null,
        vaultKeyOverride: MasterVaultKey? = null
    ): NetworkResult<Unit> {
        val vaultKey = vaultKeyOverride ?: sessionManager.getVaultKey()
            ?: return NetworkResult.Unknown(
                userMessage = "No active Master Vault Key available on this device",
                cause = IllegalStateException("MasterVaultKey not found in SessionManager")
            )

        val receiverPublicKey = try {
            EcdhPairingCrypto.hexToBytes(receiverPublicKeyHex)
        } catch (e: Exception) {
            return NetworkResult.Unknown(
                userMessage = "Invalid receiver public key hexadecimal: ${e.message}",
                cause = e
            )
        }

        // Mint registration ticket for the new device
        val ticketResult = mailboxClient.mintLinkTicket(token)
        val registrationTicket = when (ticketResult) {
            is NetworkResult.Success -> ticketResult.data.effectiveTicket
            is NetworkResult.Failure -> return ticketResult
        }

        // Encrypt compound bundle (ticket + vaultKey)
        val payloadHex = try {
            EcdhPairingCrypto.encryptCompoundPayload(
                ticketHex = registrationTicket,
                vaultKey = vaultKey,
                peerPublicKey = receiverPublicKey
            )
        } catch (e: Exception) {
            return NetworkResult.Unknown(
                userMessage = "Failed to encrypt compound pairing bundle: ${e.message}",
                cause = e
            )
        }

        return mailboxClient.fulfillMailbox(mailboxId, payloadHex, token)
    }

    /**
     * Fulfills sender session for an 8-character mailbox identifier by querying the mailbox status
     * from the server to retrieve the receiver's ephemeral public key. Retries if the peer is still
     * completing proof-of-work or network registration.
     *
     * @param mailboxId 8-character Crockford Base32 mailbox code.
     * @param token Optional explicit session token.
     * @param vaultKeyOverride Optional vault key override.
     * @param maxRetries Maximum polling attempts while awaiting peer readiness (default 10).
     * @param retryDelayMillis Delay between successive polling attempts (default 1000ms).
     * @return [NetworkResult.Success] on completion or [NetworkResult.Failure].
     */
    suspend fun fulfillSenderWithCode(
        mailboxId: String,
        token: String? = null,
        vaultKeyOverride: MasterVaultKey? = null,
        maxRetries: Int = 10,
        retryDelayMillis: Long = 1_000L
    ): NetworkResult<Unit> {
        val cleanId = mailboxId.trim().replace("-", "").replace(" ", "").lowercase()
        if (cleanId.length != 8) {
            return NetworkResult.Unknown("Pairing code must be 8 characters, got '$cleanId'")
        }

        var lastFailure: NetworkResult.Failure? = null
        for (attempt in 0 until maxRetries) {
            val statusResult = mailboxClient.checkStatus(cleanId, token)
            when (statusResult) {
                is NetworkResult.Success -> {
                    val pubTemp = statusResult.data.pubTemp
                    if (!pubTemp.isNullOrBlank()) {
                        return fulfillSenderSession(
                            mailboxId = cleanId,
                            receiverPublicKeyHex = pubTemp,
                            token = token,
                            vaultKeyOverride = vaultKeyOverride
                        )
                    }
                    lastFailure = NetworkResult.Unknown("Peer device is not ready yet (status: ${statusResult.data.status})")
                }
                is NetworkResult.Http -> {
                    if (statusResult.code == 404) {
                        lastFailure = statusResult
                    } else {
                        return statusResult
                    }
                }
                is NetworkResult.Failure -> {
                    lastFailure = statusResult
                }
            }
            if (attempt < maxRetries - 1) {
                delay(retryDelayMillis)
            }
        }

        return lastFailure ?: NetworkResult.Unknown("Timed out waiting for peer device to become ready")
    }

    // ==========================================
    // Flow B: Sender Displays QR Code
    // ==========================================

    /**
     * Initiates Flow B hosting on an authenticated device:
     * 1. Allocates an offered mailbox via `POST /api/v1/devices/mailbox` (state Waiting).
     * 2. Emits [PairingState.AwaitingJoin] with the QR payload.
     * 3. Continuously polls status via `GET /api/v1/devices/mailbox/{id}/status`.
     * 4. When status transitions to Ready with receiver's public key, encrypts and fulfills the mailbox.
     * 5. Emits [PairingState.Success].
     *
     * @param token Optional explicit bearer token.
     * @param timeoutMillis Maximum wait duration in milliseconds.
     * @return Cold [Flow] emitting progressive [PairingState] transitions.
     */
    fun startHostFlow(
        token: String? = null,
        timeoutMillis: Long = 300_000L
    ): Flow<PairingState> = flow {
        val effectiveToken = token ?: sessionManager.getToken()
        val vaultKey = sessionManager.getVaultKey()
        if (effectiveToken.isNullOrBlank() || vaultKey == null) {
            emit(PairingState.Error("Active session token and unlocked vault key required to host pairing"))
            return@flow
        }

        val allocResult = mailboxClient.allocateMailbox(effectiveToken)
        if (allocResult !is NetworkResult.Success) {
            val failure = allocResult as NetworkResult.Failure
            emit(PairingState.Error("Failed to allocate mailbox: ${failure.userMessage}"))
            return@flow
        }

        val allocation = allocResult.data
        val serverConfig = serverConfigStore.getServerConfig()
        val qrPayload = PairingQrPayload(
            mailboxId = allocation.mailboxId,
            publicKeyHex = null,
            host = serverConfig.host,
            port = serverConfig.port,
            fingerprint = serverConfig.fingerprintHex,
            mode = "sender"
        )
        val qrJson = json.encodeToString(PairingQrPayload.serializer(), qrPayload)

        emit(
            PairingState.AwaitingJoin(
                mailboxId = allocation.mailboxId,
                qrPayloadJson = qrJson,
                expiresInSeconds = allocation.expiresIn
            )
        )

        val startTime = System.currentTimeMillis()
        var consecutiveTransientFailures = 0
        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= timeoutMillis) {
                emit(PairingState.Error("Pairing host session timed out after ${timeoutMillis / 1000}s"))
                return@flow
            }

            when (val statusResult = mailboxClient.checkStatus(allocation.mailboxId, effectiveToken)) {
                is NetworkResult.Success -> {
                    consecutiveTransientFailures = 0
                    val statusData = statusResult.data
                    if (statusData.isReady) {
                        val fulfillResult = fulfillSenderSession(
                            mailboxId = allocation.mailboxId,
                            receiverPublicKeyHex = statusData.pubTemp!!,
                            token = effectiveToken,
                            vaultKeyOverride = vaultKey
                        )
                        when (fulfillResult) {
                            is NetworkResult.Success -> {
                                emit(PairingState.Success(vaultKey))
                                return@flow
                            }
                            is NetworkResult.Failure -> {
                                emit(PairingState.Error("Failed to deposit pairing payload: ${fulfillResult.userMessage}"))
                                return@flow
                            }
                        }
                    }
                }
                is NetworkResult.Failure -> {
                    if (statusResult is NetworkResult.Http && (statusResult.code == 404 || statusResult.code == 401)) {
                        emit(PairingState.Error("Mailbox session expired or invalidated: ${statusResult.userMessage}"))
                        return@flow
                    }
                    consecutiveTransientFailures++
                    if (consecutiveTransientFailures >= 5) {
                        emit(PairingState.Error("Failed to check mailbox status: ${statusResult.userMessage}"))
                        return@flow
                    }
                }
            }

            delay(1_000L)
        }
    }

    /**
     * Joins an offered mailbox (Flow B: Receiver scans sender's QR code).
     *
     * 1. Updates server endpoint configuration if host/port provided.
     * 2. Requests and solves Hashcash Proof-of-Work challenge.
     * 3. Generates ephemeral receiver X25519 keypair.
     * 4. Joins mailbox via `POST /api/auth/mailbox/{id}/join` to receive polling token.
     * 5. Polls pickup endpoint until fulfilled, decrypts payload, and saves [MasterVaultKey].
     *
     * @param mailboxId 8-character Crockford Base32 mailbox code.
     * @param host Optional server host name or IP address.
     * @param port Optional server port number.
     * @param fingerprint Optional 64-hex SHA-256 certificate fingerprint for TLS pinning.
     * @param timeoutMillis Maximum polling duration in milliseconds.
     * @return Cold [Flow] emitting progressive [PairingState] transitions.
     */
    fun startJoinFlow(
        mailboxId: String,
        host: String? = null,
        port: Int? = null,
        fingerprint: String? = null,
        timeoutMillis: Long = 300_000L
    ): Flow<PairingState> = flow {
        if (!host.isNullOrBlank()) {
            val fingerprintBytes = fingerprint?.let { hex ->
                try {
                    EcdhPairingCrypto.hexToBytes(hex).takeIf { it.size == 32 }
                } catch (_: Exception) {
                    null
                }
            } ?: serverConfigStore.getServerConfig().pinnedCertFingerprintSha256

            serverConfigStore.updateServer(
                host = host,
                port = port ?: 23342,
                useTls = fingerprint != null || serverConfigStore.getServerConfig().useTls,
                pinnedCertFingerprintSha256 = fingerprintBytes
            )
        }

        // Request PoW challenge
        val challengeResult = authClient.fetchChallenge("register")
        if (challengeResult !is NetworkResult.Success) {
            val failure = challengeResult as NetworkResult.Failure
            emit(PairingState.Error("Failed to fetch Proof-of-Work challenge: ${failure.userMessage}"))
            return@flow
        }

        // Solve PoW puzzle
        val ephemeralIdentityHash = generateEphemeralIdentityHash()
        val powHeader = try {
            hashcashSolver.solve(ephemeralIdentityHash, challengeResult.data.challenge)
        } catch (e: Exception) {
            emit(PairingState.Error("Proof-of-Work solver failed: ${e.message}", e))
            return@flow
        }

        val rxKeyPair = EcdhPairingCrypto.generateKeyPair()

        try {
            val joinResult = mailboxClient.joinMailbox(
                mailboxId = mailboxId,
                pubTempHex = EcdhPairingCrypto.bytesToHex(rxKeyPair.publicKey),
                hashcashHeader = powHeader
            )
            if (joinResult !is NetworkResult.Success) {
                val failure = joinResult as NetworkResult.Failure
                emit(PairingState.Error("Failed to join pairing mailbox: ${failure.userMessage}"))
                return@flow
            }

            emit(PairingState.AwaitingFulfillment)

            val pollingToken = joinResult.data.token
            mailboxClient.pollPickup(
                mailboxId = mailboxId,
                pollingToken = pollingToken,
                timeoutMillis = timeoutMillis,
                pollIntervalMillis = 1_500L
            ).collect { pollState ->
                when (pollState) {
                    is MailboxPollState.Pending -> {
                        emit(PairingState.AwaitingFulfillment)
                    }
                    is MailboxPollState.Fulfilled -> {
                        var bundle: project.kosh.core.crypto.PairingBundle? = null
                        try {
                            bundle = EcdhPairingCrypto.decryptCompoundPayload(
                                payloadHex = pollState.payload,
                                receiverPrivateKey = rxKeyPair.privateKey
                            )
                            sessionManager.saveVaultKey(bundle.vaultKey)
                            if (bundle.registrationTicket.isNotBlank()) {
                                sessionManager.saveRegistrationTicket(bundle.registrationTicket)
                            }
                            emit(
                                PairingState.Success(
                                    vaultKey = bundle.vaultKey,
                                    registrationTicket = bundle.registrationTicket.ifEmpty { null }
                                )
                            )
                        } catch (e: Exception) {
                            bundle?.close()
                            emit(PairingState.Error("Failed to decrypt transferred pairing bundle: ${e.message}", e))
                        }
                    }
                    is MailboxPollState.Failed -> {
                        emit(PairingState.Error(pollState.message, pollState.cause))
                    }
                }
            }
        } finally {
            rxKeyPair.close()
        }
    }

    /**
     * Backward-compatible alias for initiating sender transfer flow.
     */
    fun startSenderTransferFlow(): Flow<PairingState> = startHostFlow()

    /**
     * Backward-compatible helper to receive a pre-fulfilled vault key.
     */
    suspend fun receivePreFulfilledVaultKey(
        mailboxId: String,
        transferKeyHex: String,
        token: String? = null
    ): NetworkResult<MasterVaultKey> {
        val effectiveToken = token ?: ""
        val pickupResult = mailboxClient.pickupMailbox(mailboxId, effectiveToken)
        if (pickupResult !is NetworkResult.Success) {
            return pickupResult as NetworkResult.Failure
        }

        val payloadHex = pickupResult.data.payload
        if (payloadHex.isNullOrBlank()) {
            return NetworkResult.Unknown("Mailbox is empty or has already been consumed", null)
        }

        return try {
            val transferKey = EcdhPairingCrypto.hexToBytes(transferKeyHex)
            val vaultKey = if (payloadHex.length == project.kosh.core.crypto.AesGcmEnvelope.ENVELOPE_HEX_LENGTH) {
                project.kosh.core.crypto.AesGcmEnvelope.unwrap(payloadHex, transferKey)
            } else {
                EcdhPairingCrypto.decryptPayload(payloadHex, transferKey)
            }
            sessionManager.saveVaultKey(vaultKey)
            NetworkResult.Success(vaultKey)
        } catch (e: Exception) {
            NetworkResult.Unknown("Failed to decrypt transferred vault key: ${e.message}", e)
        }
    }

    private fun generateEphemeralIdentityHash(): String {
        val randomBytes = UUID.randomUUID().toString().toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(randomBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
