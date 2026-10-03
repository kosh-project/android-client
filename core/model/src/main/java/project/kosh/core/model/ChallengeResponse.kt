package project.kosh.core.model

import kotlinx.serialization.Serializable

/**
 * Wire-level JSON response returned by the backend Proof-of-Work challenge endpoint
 * (`GET /api/auth/challenge?action=<login|register>`).
 *
 * To mitigate brute-force credential stuffing and distributed denial-of-service attacks,
 * the Kosh server requires every authentication request to solve a dynamic Hashcash puzzle.
 * The server issues a stateless challenge string containing:
 * ```text
 * <timestamp_hex (16 chars)><mac_hex (64 chars)><action (5 or 8 chars)>
 * ```
 * - `timestamp_hex`: 64-bit unsigned Unix timestamp in hexadecimal (16 characters).
 * - `mac_hex`: A 256-bit BLAKE3 HMAC computed by the server over the timestamp, client IP, and action (64 characters).
 * - `action`: The exact action identifier (`login` or `register`).
 *
 * Expected payload length:
 * - 85 ASCII characters for `login` action.
 * - 88 ASCII characters for `register` action.
 *
 * **Important Invariant:** Challenges have a strict 15-second time-to-live (TTL). If the client
 * takes longer than 15 seconds to solve the puzzle and transmit the resulting `X-Hashcash` header,
 * the server rejects the request with HTTP 401 Unauthorized (`Challenge expired`).
 *
 * @property challenge The 85- or 88-character challenge string to be passed into [HashcashSolver].
 */
@Serializable
data class ChallengeResponse(
    val challenge: String
)
