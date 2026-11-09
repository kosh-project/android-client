package project.kosh.core.crypto

import java.security.SecureRandom
import java.util.Arrays


class MasterVaultKey(val keyBytes: ByteArray) : AutoCloseable {
    init {
        require(keyBytes.size == KEY_SIZE_BYTES) {
            "MasterVaultKey must be exactly $KEY_SIZE_BYTES bytes got, ${keyBytes.size}"
        }
    }

    var isClosed: Boolean = false
        private set

    /**
     * Wipes the sensitive 32-byte key buffer with zeroes in memory.
     */
    override fun close() {
        if (!isClosed) {
            Arrays.fill(keyBytes, 0.toByte())
            isClosed = true
        }
    }

    companion object {
        const val KEY_SIZE_BYTES = 32


        fun generate(random: SecureRandom = SecureRandom()): MasterVaultKey {
            val bytes = ByteArray(KEY_SIZE_BYTES)
            random.nextBytes(bytes)
            return MasterVaultKey(bytes)
        }
    }
}