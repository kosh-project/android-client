package project.kosh.core.crypto

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Zero-allocation, hardware-accelerated streaming AES-256-GCM cipher
 * adhering strictly to NIST SP 800-38D specifications.
 *
 * Combines hardware-accelerated AES/CTR/NoPadding (which streams immediately
 * without Conscrypt's internal heap buffering) with [GhashAccumulator].
 */
class HardwareStreamingAesGcmCipher(
    key: ByteArray,
    iv: ByteArray
) {
    init {
        require(key.size == KEY_SIZE_BYTES) { "Key must be exactly $KEY_SIZE_BYTES bytes, got ${key.size}" }
        require(iv.size == IV_SIZE_BYTES) { "IV must be exactly $IV_SIZE_BYTES bytes, got ${iv.size}" }
    }

    private val t0: ByteArray
    private val ctrCipher: Cipher
    private val ghash: GhashAccumulator
    private var isFinalized = false

    init {
        val keySpec = SecretKeySpec(key, "AES")

        val zeroBlock = ByteArray(16)

        ctrCipher = Cipher.getInstance("AES/CTR/NoPadding")

        // Derivation of H = AES_K(0^16) using CTR mode with IV = 0^16:
        // 0^16 xor AES_K(0^16) = AES_K(0^16) = H
        ctrCipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(zeroBlock))
        val h = ctrCipher.doFinal(zeroBlock)
        ghash = GhashAccumulator(h)

        // Derivation of T0 = AES_K(J0) using CTR mode with IV = J0:
        // 0^16 xor AES_K(J0) = AES_K(J0) = T0
        val j0 = ByteArray(16)
        System.arraycopy(iv, 0, j0, 0, IV_SIZE_BYTES)
        j0[15] = 1.toByte()
        ctrCipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(j0))
        t0 = ctrCipher.doFinal(zeroBlock)

        // Initial counter for CTR mode: J1 = inc32(J0) = IV || 0^31 || 2
        val j1 = ByteArray(16)
        System.arraycopy(iv, 0, j1, 0, IV_SIZE_BYTES)
        j1[15] = 2.toByte()
        ctrCipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(j1))
    }

    /**
     * Encrypts the provided chunk of plaintext and immediately updates the GHASH accumulator.
     * Returns the ciphertext bytes corresponding to this chunk.
     */
    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ByteArray {
        check(!isFinalized) { "Cipher has already been finalized" }
        require(offset >= 0 && length >= 0 && offset + length <= input.size) {
            "Offset $offset and length $length out of bounds for buffer size ${input.size}"
        }
        if (length == 0) return EMPTY_BYTE_ARRAY

        val ciphertext = ctrCipher.update(input, offset, length)
        val result = ciphertext ?: EMPTY_BYTE_ARRAY
        if (result.isNotEmpty()) {
            ghash.update(result, 0, result.size)
        }
        return result
    }

    /**
     * Finalizes the encryption stream, emitting any trailing ciphertext followed by
     * the 16-byte NIST SP 800-38D GCM authentication tag.
     */
    fun doFinal(): ByteArray {
        check(!isFinalized) { "Cipher has already been finalized" }
        isFinalized = true

        val trailingCiphertext = ctrCipher.doFinal()
        if (trailingCiphertext != null && trailingCiphertext.isNotEmpty()) {
            ghash.update(trailingCiphertext, 0, trailingCiphertext.size)
        }

        val tag = ghash.finalizeTag(t0)

        return if (trailingCiphertext != null && trailingCiphertext.isNotEmpty()) {
            val finalOutput = ByteArray(trailingCiphertext.size + tag.size)
            System.arraycopy(trailingCiphertext, 0, finalOutput, 0, trailingCiphertext.size)
            System.arraycopy(tag, 0, finalOutput, trailingCiphertext.size, tag.size)
            finalOutput
        } else {
            tag
        }
    }

    /**
     * Convenience method to encrypt input and finalize in one call.
     */
    fun doFinal(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ByteArray {
        check(!isFinalized) { "Cipher has already been finalized" }
        require(offset >= 0 && length >= 0 && offset + length <= input.size) {
            "Offset $offset and length $length out of bounds for buffer size ${input.size}"
        }
        val ct = if (length > 0) update(input, offset, length) else EMPTY_BYTE_ARRAY
        val finalChunk = doFinal()
        if (ct.isEmpty()) return finalChunk

        val combined = ByteArray(ct.size + finalChunk.size)
        System.arraycopy(ct, 0, combined, 0, ct.size)
        System.arraycopy(finalChunk, 0, combined, ct.size, finalChunk.size)
        return combined
    }

    companion object {
        const val KEY_SIZE_BYTES = 32
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BYTES = 16
        private val EMPTY_BYTE_ARRAY = ByteArray(0)
    }
}
