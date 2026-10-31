package project.kosh.core.crypto

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Encrypted (E2EE) cryptographic engine for device-to-device pairing.
 *
 * Implements:
 * 1. RFC 7748 X25519 scalar multiplication and keypair generation.
 * 2. RFC 5869 HKDF-SHA256 key derivation.
 * 3. AES-256-GCM authenticated payload envelope packing (92 raw bytes / 184 hex characters).
 *
 * Wire payload format (184 hex characters):
 * ```text
 * [pk_tx: 32 bytes][IV / Nonce: 12 bytes][Ciphertext: 32 bytes][GCM Tag: 16 bytes]
 * ```
 */
object EcdhPairingCrypto {

    const val PUBLIC_KEY_SIZE = 32
    const val PRIVATE_KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val VAULT_KEY_SIZE = 32
    const val GCM_TAG_SIZE = 16
    const val CIPHERTEXT_WITH_TAG_SIZE = VAULT_KEY_SIZE + GCM_TAG_SIZE // 48 bytes
    const val ENVELOPE_RAW_BYTES = PUBLIC_KEY_SIZE + NONCE_SIZE + CIPHERTEXT_WITH_TAG_SIZE // 92 bytes
    const val ENVELOPE_HEX_LENGTH = ENVELOPE_RAW_BYTES * 2 // 184 hex chars

    const val KDF_INFO = "kosh-device-pairing-v1"
    private const val AES_GCM_ALGORITHM = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"

    private val P: BigInteger = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE_POINT_U: ByteArray = ByteArray(32).apply { this[0] = 9 }

    /**
     * Generates a fresh ephemeral X25519 keypair.
     */
    fun generateKeyPair(random: SecureRandom = SecureRandom()): X25519KeyPair {
        val privateKey = ByteArray(PRIVATE_KEY_SIZE)
        random.nextBytes(privateKey)
        val publicKey = scalarMult(privateKey, BASE_POINT_U)
        return X25519KeyPair(privateKey, publicKey)
    }

    /**
     * Performs RFC 7748 Curve25519 scalar multiplication.
     *
     * @param scalar 32-byte private scalar.
     * @param uCoordinate 32-byte peer public u-coordinate.
     * @return 32-byte computed shared secret or public coordinate.
     */
    fun scalarMult(scalar: ByteArray, uCoordinate: ByteArray): ByteArray {
        require(scalar.size == PRIVATE_KEY_SIZE) { "Scalar must be $PRIVATE_KEY_SIZE bytes" }
        require(uCoordinate.size == PUBLIC_KEY_SIZE) { "u-coordinate must be $PUBLIC_KEY_SIZE bytes" }

        val clamped = clampScalar(scalar)
        val u = decodeUCoordinate(uCoordinate)

        val x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val byteIdx = t / 8
            val bitIdx = t % 8
            val kt = (clamped[byteIdx].toInt() shr bitIdx) and 1
            swap = swap xor kt
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = kt

            val a = (x2 + z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            x3 = (da + cb).mod(P).pow(2).mod(P)
            z3 = x1.multiply((da - cb).mod(P).pow(2)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply((aa + A24.multiply(e)).mod(P)).mod(P)
        }

        if (swap == 1) {
            var tmp = x2; x2 = x3; x3 = tmp
            tmp = z2; z2 = z3; z3 = tmp
        }

        val result = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P)
        return encodeUCoordinate(result)
    }

    /**
     * Computes the Diffie-Hellman shared secret between [privateKey] and [peerPublicKey].
     *
     * Validates that the shared secret is not the all-zero value (protecting against
     * small-order subgroup attacks per RFC 7748 Section 6.1).
     */
    fun computeSharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val ss = scalarMult(privateKey, peerPublicKey)
        if (isAllZero(ss)) {
            throw IllegalArgumentException("Invalid X25519 public key: shared secret is all zeros (small-order subgroup attack)")
        }
        return ss
    }

    /**
     * Derives a 32-byte symmetric key from [sharedSecret] using HKDF-SHA256 (RFC 5869).
     */
    fun derivePairingKey(sharedSecret: ByteArray, info: String = KDF_INFO): ByteArray {
        val prk = hkdfExtract(salt = null, ikm = sharedSecret)
        return hkdfExpand(prk, info.toByteArray(Charsets.UTF_8), outLen = 32)
    }

    /**
     * Encrypts the [vaultKey] using ephemeral X25519 key agreement with [peerPublicKey].
     *
     * @param vaultKey Master Vault Key to transmit.
     * @param peerPublicKey The receiver's 32-byte public key.
     * @param random Cryptographically secure RNG.
     * @return 184-character hexadecimal wire envelope.
     */
    fun encryptPayload(
        vaultKey: MasterVaultKey,
        peerPublicKey: ByteArray,
        random: SecureRandom = SecureRandom()
    ): String {
        require(peerPublicKey.size == PUBLIC_KEY_SIZE) { "Peer public key must be $PUBLIC_KEY_SIZE bytes" }

        val txKeyPair = generateKeyPair(random)
        try {
            val sharedSecret = computeSharedSecret(txKeyPair.privateKey, peerPublicKey)
            val symmetricKey = derivePairingKey(sharedSecret)

            val iv = ByteArray(NONCE_SIZE)
            random.nextBytes(iv)

            val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            val keySpec = SecretKeySpec(symmetricKey, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val ciphertextAndTag = cipher.doFinal(vaultKey.keyBytes)
            require(ciphertextAndTag.size == CIPHERTEXT_WITH_TAG_SIZE) {
                "Encrypted ciphertext length mismatch: expected $CIPHERTEXT_WITH_TAG_SIZE, got ${ciphertextAndTag.size}"
            }

            val payload = ByteArray(ENVELOPE_RAW_BYTES)
            System.arraycopy(txKeyPair.publicKey, 0, payload, 0, PUBLIC_KEY_SIZE)
            System.arraycopy(iv, 0, payload, PUBLIC_KEY_SIZE, NONCE_SIZE)
            System.arraycopy(ciphertextAndTag, 0, payload, PUBLIC_KEY_SIZE + NONCE_SIZE, CIPHERTEXT_WITH_TAG_SIZE)

            Arrays.fill(sharedSecret, 0.toByte())
            Arrays.fill(symmetricKey, 0.toByte())

            return bytesToHex(payload)
        } finally {
            txKeyPair.close()
        }
    }

    /**
     * Decrypts the [payloadHex] using [receiverPrivateKey].
     *
     * @param payloadHex 184-character hexadecimal wire envelope.
     * @param receiverPrivateKey The receiver's 32-byte private key.
     * @return The recovered [MasterVaultKey].
     */
    fun decryptPayload(payloadHex: String, receiverPrivateKey: ByteArray): MasterVaultKey {
        require(payloadHex.length == ENVELOPE_HEX_LENGTH) {
            "Payload length must be exactly $ENVELOPE_HEX_LENGTH hex chars (92 bytes), got ${payloadHex.length}"
        }
        require(receiverPrivateKey.size == PRIVATE_KEY_SIZE) {
            "Receiver private key must be $PRIVATE_KEY_SIZE bytes"
        }

        val rawPayload = hexToBytes(payloadHex)
        val txPublicKey = rawPayload.copyOfRange(0, PUBLIC_KEY_SIZE)
        val iv = rawPayload.copyOfRange(PUBLIC_KEY_SIZE, PUBLIC_KEY_SIZE + NONCE_SIZE)
        val ciphertextAndTag = rawPayload.copyOfRange(PUBLIC_KEY_SIZE + NONCE_SIZE, ENVELOPE_RAW_BYTES)

        val sharedSecret = computeSharedSecret(receiverPrivateKey, txPublicKey)
        val symmetricKey = derivePairingKey(sharedSecret)

        try {
            val cipher = Cipher.getInstance(AES_GCM_ALGORITHM)
            val keySpec = SecretKeySpec(symmetricKey, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

            val decryptedBytes = cipher.doFinal(ciphertextAndTag)
            require(decryptedBytes.size == VAULT_KEY_SIZE) {
                "Decrypted key size mismatch: expected $VAULT_KEY_SIZE bytes, got ${decryptedBytes.size}"
            }

            return MasterVaultKey(decryptedBytes)
        } finally {
            Arrays.fill(sharedSecret, 0.toByte())
            Arrays.fill(symmetricKey, 0.toByte())
        }
    }

    private fun clampScalar(scalar: ByteArray): ByteArray {
        val k = scalar.copyOf(PRIVATE_KEY_SIZE)
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return k
    }

    private fun decodeUCoordinate(uBytes: ByteArray): BigInteger {
        val copy = uBytes.copyOf(PUBLIC_KEY_SIZE)
        copy[31] = (copy[31].toInt() and 127).toByte()
        val reversed = copy.reversedArray()
        return BigInteger(1, reversed)
    }

    private fun encodeUCoordinate(num: BigInteger): ByteArray {
        val result = ByteArray(PUBLIC_KEY_SIZE)
        val bigEndian = num.toByteArray()
        var srcPos = 0
        var len = bigEndian.size
        if (len > PUBLIC_KEY_SIZE && bigEndian[0] == 0.toByte()) {
            srcPos = 1
            len--
        }
        for (i in 0 until len) {
            result[i] = bigEndian[srcPos + len - 1 - i]
        }
        return result
    }

    private fun isAllZero(bytes: ByteArray): Boolean {
        var acc = 0
        for (b in bytes) {
            acc = acc or b.toInt()
        }
        return acc == 0
    }

    private fun hkdfExtract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(effectiveSalt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, outLen: Int = 32): ByteArray {
        require(outLen in 1..8160)
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))
        val result = ByteArray(outLen)
        var t = ByteArray(0)
        var generated = 0
        var round = 1
        while (generated < outLen) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(round.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, outLen - generated)
            System.arraycopy(t, 0, result, generated, toCopy)
            generated += toCopy
            round++
        }
        return result
    }

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Hex string length must be even" }
        val res = ByteArray(hex.length / 2)
        for (i in res.indices) {
            res[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return res
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
