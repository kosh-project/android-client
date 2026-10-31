package project.kosh.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesGcmEnvelope {
    const val IV_SIZE_BYTES = 12
    const val KEY_SIZE_BYTES = 32
    const val TAG_SIZE_BITS = 128
    const val TAG_SIZE_BYTES = 16
    const val ENVELOPE_RAW_BYTES = IV_SIZE_BYTES + KEY_SIZE_BYTES + TAG_SIZE_BYTES
    const val ENVELOPE_HEX_LENGTH = ENVELOPE_RAW_BYTES * 2

    private const val ALGORITHM = "AES/GCM/NoPadding"

    /**
     * Wraps the [masterKey] using [kek] into a 120-character hex string.
     *
     * @param masterKey The 32-byte Master Vault Key to protect.
     * @param kek The 32-byte Key Encryption Key derived via Argon2id.
     * @param random Cryptographically secure RNG for the 12-byte IV.
     * @return 120-character lowercase hexadecimal envelope string.
     */
    fun wrap(
        masterKey: MasterVaultKey,
        kek: ByteArray,
        random: SecureRandom = SecureRandom()
    ): String {
        require(kek.size == KEY_SIZE_BYTES) { "KEK must be exactly $KEY_SIZE_BYTES" }

        val iv = ByteArray(IV_SIZE_BYTES)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(kek, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val cipherTextAndTag = cipher.doFinal(masterKey.keyBytes)

        val envelopeBytes = ByteArray(ENVELOPE_RAW_BYTES)
        System.arraycopy(iv, 0, envelopeBytes, 0, IV_SIZE_BYTES)
        System.arraycopy(cipherTextAndTag, 0, envelopeBytes, IV_SIZE_BYTES, cipherTextAndTag.size)

        return envelopeBytes.joinToString ("") { "%02x".format(it) }
    }


    fun unwrap(envelopeHex: String, kek: ByteArray): MasterVaultKey {
        require(envelopeHex.length == ENVELOPE_HEX_LENGTH) {
            "Envelope hex must be exactly $ENVELOPE_HEX_LENGTH, got ${envelopeHex.length}"
        }

        require(kek.size == KEY_SIZE_BYTES) {
            "KEK must be exactly $KEY_SIZE_BYTES bytes"
        }

        val envelopeByte = hexToBytes(envelopeHex)
        val iv = envelopeByte.copyOfRange(0 , IV_SIZE_BYTES)
        val ciphertextAndTag = envelopeByte.copyOfRange(IV_SIZE_BYTES, ENVELOPE_RAW_BYTES)

        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(kek, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        val decryptedKeyBytes = cipher.doFinal(ciphertextAndTag)

        return MasterVaultKey(decryptedKeyBytes)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val index = i * 2
            bytes[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return bytes
    }
}