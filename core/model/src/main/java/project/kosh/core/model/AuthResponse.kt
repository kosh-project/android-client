package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


/**
 * Wire-level JSON response returned by the backend upon successful authentication (`POST /api/auth/login`).
 *
 * The issued [token] is not an arbitrary string or opaque session cookie, but a structured
 * 118-byte composite token with the canonical layout:
 * ```text
 * <session_id>.<expires_at_hex>.<blake3_mac>
 * ```
 * - `session_id` (36 ASCII characters): A standard RFC 4122 UUID v4 identifying the active session.
 * - `expires_at_hex` (16 ASCII characters): A 64-bit unsigned Unix timestamp in hex representing expiration.
 * - `blake3_mac` (64 ASCII characters): A keyed BLAKE3 MAC computed by the server over the session ID and expiry.
 *
 * This design enables the server's ingress filter (`mac_guard`) to verify token integrity and expiration
 * entirely in memory in sub-microsecond time before executing any SQLite database queries.
 *
 * On the Android client, this token is stored in hardware-encrypted storage via [SessionManager].
 *
 * @property token The raw 118-byte composite bearer token issued by the server.
 */
@Serializable
data class AuthResponse(
    val token: String,

    @SerialName("wrapped_vault_key")
    val wrappedVaultKey: String
)
