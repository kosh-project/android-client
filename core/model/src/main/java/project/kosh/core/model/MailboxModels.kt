package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-level JSON response returned by `POST /api/v1/devices/mailbox`.
 *
 * The server allocates an ephemeral rendezvous mailbox in RAM with a 300-second TTL.
 *
 * @property mailboxId 8-character lowercase Crockford Base32 identifier (e.g. `c7m9x4b2`).
 * @property expiresIn Time-to-live in seconds before the mailbox expires in server RAM.
 */
@Serializable
data class MailboxAllocationResponse(
    @SerialName("mailbox_id")
    val mailboxId: String,

    @SerialName("expires_in")
    val expiresIn: Long
)

/**
 * Wire-level JSON request body for `POST /api/v1/devices/mailbox/{id}/fulfill`.
 *
 * The [payload] must be between 120 and 8,192 hexadecimal characters (representing
 * 60 to 4,096 raw bytes), containing the sender device's ephemeral public key,
 * nonce, and ciphertext authenticating the master key envelope.
 *
 * @property payload Hexadecimal-encoded E2EE key exchange payload.
 */
@Serializable
data class MailboxFulfillRequest(
    val payload: String
) {
    init {
        require(payload.length in 120..8192) {
            "Payload length must be between 120 and 8192 hex characters, got ${payload.length}"
        }
    }
}

/**
 * Wire-level JSON response returned by `GET /api/v1/devices/mailbox/{id}`.
 *
 * When pending fulfillment by the peer device, the response contains `{"status": "pending"}`.
 * Once fulfilled, the server returns `{"status": "fulfilled", "payload": "<hex>"}` and
 * immediately evicts the record from RAM ("single-use burn-after-reading").
 *
 * @property status Current mailbox state ("pending" or "fulfilled").
 * @property payload Hexadecimal-encoded payload deposited by the sender device, if fulfilled.
 */
@Serializable
data class MailboxPickupResponse(
    val status: String,
    val payload: String? = null
) {
    /**
     * Whether the mailbox has been successfully fulfilled by the peer device with a valid payload.
     */
    val isFulfilled: Boolean
        get() = status.equals("fulfilled", ignoreCase = true) && !payload.isNullOrBlank()

    /**
     * Whether the mailbox is currently pending fulfillment by the peer device.
     */
    val isPending: Boolean
        get() = status.equals("pending", ignoreCase = true)
}

/**
 * Domain-level polling state emitted during the lifecycle of device pairing.
 */
sealed interface MailboxPollState {
    /**
     * Mailbox is allocated and waiting for the sender device to deposit the encrypted payload.
     */
    data object Pending : MailboxPollState

    /**
     * Mailbox has been fulfilled and picked up. Contains the raw hex payload.
     * Note: The server has burned the mailbox from RAM upon returning this payload.
     */
    data class Fulfilled(val payload: String) : MailboxPollState

    /**
     * Mailbox polling timed out or encountered an unrecoverable failure.
     */
    data class Failed(val message: String, val cause: Throwable? = null) : MailboxPollState
}

/**
 * Portable payload encoded into a QR code displayed on the receiver device
 * during device-to-device pairing.
 *
 * @property mailboxId 8-character lowercase Crockford Base32 mailbox code.
 * @property publicKeyHex 64-hex-character string representing receiver's 32-byte X25519 public key.
 * @property host Optional server host name or IP address.
 * @property port Optional server port number.
 * @property fingerprint Optional SHA-256 certificate fingerprint for TLS pinning.
 */
@Serializable
data class PairingQrPayload(
    @SerialName("mailbox_id")
    val mailboxId: String,

    @SerialName("public_key")
    val publicKeyHex: String,

    val host: String? = null,
    val port: Int? = null,
    val fingerprint: String? = null
)

