package project.kosh.core.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class HashcashSolver @Inject constructor() {

    companion object {
        private val HEX_CHARS = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        private const val CANCELLATION_CHECK_INTERVAL = 1024L
    }

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