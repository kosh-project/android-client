package project.kosh.core.crypto

import java.security.SecureRandom
import java.util.Arrays


class MasterVaultKey(val keyBytes: ByteArray) : AutoCloseable {
    init {
        require(keyBytes.size == KEY_SIZE_BYTES) {
            "MasterVaultKey must be exactly $KEY_SIZE_BYTES bytes got, ${keyBytes.size}"
        }
    }

    override fun close() {
        Arrays.fill(keyBytes, 0.toByte())
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