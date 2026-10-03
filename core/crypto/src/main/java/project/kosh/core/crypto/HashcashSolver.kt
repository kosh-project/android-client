package project.kosh.core.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * High-performance, zero-allocation Proof-of-Work (PoW) challenge solver.
 *
 * To protect against credential stuffing, distributed denial-of-service, and auth route saturation
 * without relying on centralized third-party captchas, the Kosh Rust/Axum server requires every
 * authentication request (`/api/auth/register` and `/api/auth/login`) to present a valid `X-Hashcash` header.
 *
 * ### Wire Header Layout
 * The solver constructs a contiguous ASCII byte buffer with the following structure:
 * ```text
 * Offset 00..64 : identity_hash  (64 hex chars - SHA-256 of username)
 * Offset 64..80 : nonce          (16 hex chars - 64-bit unsigned integer formatted in hex)
 * Offset 80..96 : timestamp_hex  (16 hex chars - extracted from challenge)
 * Offset 96..160: mac_hex        (64 hex chars - BLAKE3 HMAC from challenge)
 * Offset 160..  : action         (5 chars "login" or 8 chars "register")
 * ```
 * Total payload length is exactly 165 bytes for login, or 168 bytes for register.
 *
 * ### Target Condition
 * The solver increments [nonce] until:
 * ```text
 * digest = SHA-256(buffer)
 * digest[0] == 0x00 && digest[1] == 0x00 (16 leading zero bits)
 * ```
 * On average, this requires $2^{16} = 65,536$ hash iterations, which executes in ~20ms–80ms on
 * modern mobile ARM cores.
 *
 * ### Performance & Zero-Allocation Invariants
 * - **Pre-allocated byte buffer:** Memory is allocated once at function entry.
 * - **In-place hex formatting:** The 16-character hex nonce is rendered into the buffer using bit-shifts
 *   (`ushr 4`) and direct indexing into [HEX_CHARS]. No intermediate [String] allocations occur in the loop.
 * - **Single MessageDigest instance:** The SHA-256 digest engine is reused across iterations.
 * - **Cooperative cancellation:** Evaluates [ensureActive] every 1024 iterations using a fast bitwise mask
 *   to allow prompt coroutine teardown without penalizing inner loop throughput.
 * - **Thread dispatching:** Executed strictly within [Dispatchers.Default] to protect the Main Looper.
 */
@Singleton
class HashcashSolver @Inject constructor() {

    companion object {
        /**
         * Lookup table for fast, allocation-free ASCII hexadecimal character encoding.
         */
        private val HEX_CHARS = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

        /**
         * Iteration interval between cooperative coroutine cancellation checks.
         *
         * Testing cancellation via [ensureActive] on every iteration introduces function call overhead.
         * Checking every 1024 iterations (`nonce and 1023L == 0L`) balances responsiveness with CPU throughput.
         */
        private const val CANCELLATION_CHECK_INTERVAL = 1024L
    }

    /**
     * Solves the Proof-of-Work puzzle for the given [identityHash] and [challenge].
     *
     * Constructs the canonical `X-Hashcash` header and iterates nonces on `Dispatchers.Default`
     * until the 16-bit leading zero target condition is satisfied.
     *
     * @param identityHash 64-character lowercase hexadecimal SHA-256 digest of the username.
     * @param challenge 85-character (login) or 88-character (register) challenge received from the backend.
     * @return The completed 165-byte or 168-byte ASCII string ready for injection into the `X-Hashcash` HTTP header.
     * @throws IllegalArgumentException If [identityHash] is not 64 chars or [challenge] is not 85 or 88 chars.
     */
    suspend fun solve(identityHash: String, challenge: String): String =
        withContext(Dispatchers.Default) {
            require(identityHash.length == 64) {
                "identity_hash must be exactly 64 hex characters, got ${identityHash.length}"
            }

            require(challenge.length == 85 || challenge.length == 88) {
                "Challenge must be 85 chars (login) or 88 chars (register), got ${challenge.length}"
            }

            val totalLength = 64 + 16 + challenge.length
            val buffer = ByteArray(totalLength)

            val idBytes = identityHash.toByteArray(Charsets.US_ASCII)
            System.arraycopy(idBytes, 0, buffer, 0, 64)

            val challengeBytes = challenge.toByteArray(Charsets.US_ASCII)
            System.arraycopy(challengeBytes, 0, buffer, 80, challenge.length)

            val md = MessageDigest.getInstance("SHA-256")
            val digestOutput = ByteArray(32)

            var nonce = 0L

            while (true) {
                if ((nonce and (CANCELLATION_CHECK_INTERVAL - 1)) == 0L) {
                    coroutineContext.ensureActive()
                }

                var tempNonce = nonce
                for (i in 15 downTo 0) {
                    buffer[64 + i] = HEX_CHARS[(tempNonce and 0xFL).toInt()]
                    tempNonce = tempNonce ushr 4
                }

                md.update(buffer, 0, totalLength)
                md.digest(digestOutput, 0, 32)

                if (digestOutput[0] == 0.toByte() && digestOutput[1] == 0.toByte()) {
                    return@withContext String(buffer, Charsets.US_ASCII)
                }

                nonce++
            }

            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        }
}