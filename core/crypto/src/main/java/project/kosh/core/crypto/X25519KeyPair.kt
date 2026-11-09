package project.kosh.core.crypto

import java.util.Arrays

/**
 * Ephemeral X25519 asymmetric key pair used during device-to-device pairing.
 *
 * Implements [AutoCloseable] to zeroize the sensitive 32-byte [privateKey] in memory
 * when the handshake completes or is aborted.
 *
 * @property privateKey The 32-byte ephemeral private scalar.
 * @property publicKey The 32-byte public key point on Curve25519 ($u$-coordinate).
 */
class X25519KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray
) : AutoCloseable {
    init {
        require(privateKey.size == 32) {
            "X25519 private key must be exactly 32 bytes, got ${privateKey.size}"
        }
        require(publicKey.size == 32) {
            "X25519 public key must be exactly 32 bytes, got ${publicKey.size}"
        }
    }

    var isClosed: Boolean = false
        private set

    /**
     * Wipes the private key buffer with zeroes to prevent leakage in heap dumps.
     */
    override fun close() {
        if (!isClosed) {
            Arrays.fill(privateKey, 0.toByte())
            isClosed = true
        }
    }
}
