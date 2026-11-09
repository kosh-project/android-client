package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-level JSON request body for opening an ephemeral pairing mailbox (`POST /api/auth/mailbox/open`).
 *
 * @property pubTemp 64-character hexadecimal string representing the caller's 32-byte X25519 public key.
 */
@Serializable
data class MailboxOpenRequest(
    @SerialName("pub_temp")
    val pubTemp: String
) {
    init {
        require(pubTemp.length == 64) {
            "pub_temp must be exactly 64 hex characters (32 bytes), got ${pubTemp.length}"
        }
    }
}

/**
 * Wire-level JSON response returned by `POST /api/auth/mailbox/open`.
 *
 * @property mailboxId 8-character lowercase Crockford Base32 identifier.
 * @property pollingToken 64-character hexadecimal polling authorization token.
 * @property expiresIn Time-to-live in seconds before the mailbox expires in server RAM.
 */
@Serializable
data class MailboxOpenResponse(
    @SerialName("mailbox_id")
    val mailboxId: String,

    @SerialName("polling_token")
    val pollingToken: String,

    @SerialName("expires_in")
    val expiresIn: Long
)

/**
 * Wire-level JSON request body for joining an offered mailbox (`POST /api/auth/mailbox/{id}/join`).
 *
 * @property pubTemp 64-character hexadecimal string representing the caller's 32-byte X25519 public key.
 */
@Serializable
data class MailboxJoinRequest(
    @SerialName("pub_temp")
    val pubTemp: String
) {
    init {
        require(pubTemp.length == 64) {
            "pub_temp must be exactly 64 hex characters (32 bytes), got ${pubTemp.length}"
        }
    }
}

/**
 * Wire-level JSON response returned by `POST /api/auth/mailbox/{id}/join`.
 *
 * @property token 64-character hexadecimal polling authorization token.
 */
@Serializable
data class MailboxJoinResponse(
    val token: String
)

/**
 * Wire-level JSON response returned by `GET /api/v1/devices/mailbox/{id}/status`.
 *
 * @property status Current mailbox status ("waiting" or "ready").
 * @property pubTemp 64-character hexadecimal peer ephemeral public key if ready.
 */
@Serializable
data class MailboxStatusResponse(
    val status: String,

    @SerialName("pub_temp")
    val pubTemp: String? = null
) {
    val isWaiting: Boolean
        get() = status.equals("waiting", ignoreCase = true)

    val isReady: Boolean
        get() = status.equals("ready", ignoreCase = true) && !pubTemp.isNullOrBlank()
}

/**
 * Wire-level JSON response returned by `POST /api/v1/devices/link-ticket`.
 *
 * @property ticket 64-character hexadecimal single-use registration ticket.
 * @property registrationTicket Protocol alias for specification compatibility.
 * @property expiresIn Time-to-live in seconds before the ticket expires in server RAM.
 */
@Serializable
data class LinkTicketResponse(
    val ticket: String = "",

    @SerialName("registration_ticket")
    val registrationTicket: String = "",

    @SerialName("expires_in")
    val expiresIn: Long = 300L
) {
    val effectiveTicket: String
        get() = ticket.ifEmpty { registrationTicket }
}

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
 * Portable payload encoded into a QR code displayed during device-to-device pairing.
 *
 * @property mailboxId 8-character lowercase Crockford Base32 mailbox code.
 * @property publicKeyHex Optional 64-hex-character string representing ephemeral X25519 public key.
 * @property host Optional server host name or IP address.
 * @property port Optional server port number.
 * @property fingerprint Optional SHA-256 certificate fingerprint for TLS pinning.
 * @property token Optional token passed in local pairing environments.
 * @property mode Optional pairing mode ("receiver" for Flow A, "sender" for Flow B).
 */
@Serializable
data class PairingQrPayload(
    @SerialName("mailbox_id")
    val mailboxId: String,

    @SerialName("public_key")
    val publicKeyHex: String? = null,

    val host: String? = null,
    val port: Int? = null,
    val fingerprint: String? = null,
    val token: String? = null,
    val mode: String? = null
)

