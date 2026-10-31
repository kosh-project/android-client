package project.kosh.core.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import project.kosh.core.crypto.EcdhPairingCrypto
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.model.MailboxPollState
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PairingQrPayload
import project.kosh.core.network.MailboxClient
import project.kosh.core.network.ServerConfigurationStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain-level pairing state emitted during the device-to-device pairing lifecycle.
 */
sealed interface PairingState {
    /**
     * Mailbox allocated, ephemeral X25519 keys generated, and ready for sender to scan QR / input code.
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
     * Mailbox is waiting for the sender device to deposit the encrypted payload.
     */
    data object AwaitingFulfillment : PairingState

    /**
     * Successfully received, decrypted, and authenticated the transferred [MasterVaultKey],
     * and securely persisted it into hardware-backed [SessionManager] storage.
     */
    data class Success(val vaultKey: MasterVaultKey) : PairingState

    /**
     * Pairing failed due to timeout, network failure, or cryptographic integrity check failure.
     */
    data class Error(val message: String, val cause: Throwable? = null) : PairingState
}

/**
 * High-level coordinator managing End-to-End Encrypted (E2EE) device-to-device pairing.
 *
 * Coordinates between:
 * - [MailboxClient]: In-memory ephemeral rendezvous relay on the Axum server.
 * - [EcdhPairingCrypto]: RFC 7748 X25519 key exchange, HKDF key derivation, and AES-256-GCM envelope cipher.
 * - [SessionManager]: Hardware-backed keystore persistence for root vault credentials.
 */
@Singleton
class DevicePairingManager @Inject constructor(
    private val mailboxClient: MailboxClient,
    private val sessionManager: SessionManager,
    private val serverConfigStore: ServerConfigurationStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Initiates an onboarding pairing receiver flow on this device.
     *
     * 1. Allocates an 8-character Crockford Base32 mailbox on the server via [MailboxClient.allocateMailbox].
     * 2. Generates an ephemeral X25519 keypair.
     * 3. Emits [PairingState.Ready] containing the [PairingQrPayload] for QR display.
     * 4. Continuously polls the mailbox until fulfillment or timeout.
     * 5. Decrypts and authenticates the transferred [MasterVaultKey].
     * 6. Securely stores the [MasterVaultKey] into [SessionManager].
     * 7. Emits [PairingState.Success] or [PairingState.Error].
     *
     * @param token Optional bearer token if authenticating with an existing session.
     * @param timeoutMillis Maximum polling duration in milliseconds (default 300 seconds / 5 minutes).
     * @return Cold [Flow] emitting progressive [PairingState] transitions.
     */
    fun startReceiverFlow(
        token: String? = null,
        timeoutMillis: Long = 300_000L
    ): Flow<PairingState> = flow {
        val allocationResult = mailboxClient.allocateMailbox(token)
        if (allocationResult !is NetworkResult.Success) {
            val failure = allocationResult as NetworkResult.Failure
            emit(PairingState.Error("Failed to allocate mailbox: ${failure.userMessage}"))
            return@flow
        }

        val allocation = allocationResult.data
        val rxKeyPair = EcdhPairingCrypto.generateKeyPair()

        try {
            val serverConfig = serverConfigStore.getServerConfig()
            val qrPayload = PairingQrPayload(
                mailboxId = allocation.mailboxId,
                publicKeyHex = EcdhPairingCrypto.bytesToHex(rxKeyPair.publicKey),
                host = serverConfig.host,
                port = serverConfig.port,
                fingerprint = serverConfig.fingerprintHex
            )
            val qrJson = json.encodeToString(PairingQrPayload.serializer(), qrPayload)

            emit(
                PairingState.Ready(
                    mailboxId = allocation.mailboxId,
                    qrPayloadJson = qrJson,
                    expiresInSeconds = allocation.expiresIn
                )
            )

            mailboxClient.pollPickup(
                mailboxId = allocation.mailboxId,
                token = token,
                timeoutMillis = timeoutMillis
            ).collect { pollState ->
                when (pollState) {
                    is MailboxPollState.Pending -> {
                        emit(PairingState.AwaitingFulfillment)
                    }
                    is MailboxPollState.Fulfilled -> {
                        try {
                            val recoveredVaultKey = EcdhPairingCrypto.decryptPayload(
                                payloadHex = pollState.payload,
                                receiverPrivateKey = rxKeyPair.privateKey
                            )
                            sessionManager.saveVaultKey(recoveredVaultKey)
                            emit(PairingState.Success(recoveredVaultKey))
                        } catch (e: Exception) {
                            emit(PairingState.Error("Failed to decrypt transferred vault key: ${e.message}", e))
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
     * Executes sender fulfillment: transfers the active [MasterVaultKey] to the receiver device.
     *
     * 1. Resolves the active [MasterVaultKey] from [SessionManager] (or accepts [vaultKeyOverride]).
     * 2. Parses the receiver's X25519 public key.
     * 3. Generates ephemeral sender keypair and performs X25519 ECDH + HKDF key agreement.
     * 4. AES-256-GCM encrypts the master key into a 92-byte / 184-hex-char envelope.
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

        val payloadHex = try {
            EcdhPairingCrypto.encryptPayload(vaultKey, receiverPublicKey)
        } catch (e: Exception) {
            return NetworkResult.Unknown(
                userMessage = "Failed to encrypt vault key with peer public key: ${e.message}",
                cause = e
            )
        }

        return mailboxClient.fulfillMailbox(mailboxId, payloadHex, token)
    }
}
