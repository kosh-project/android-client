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
    const val ENVELOPE_RAW_BYTES = IV_SIZE_BYTES + KEY_SIZE_BYTES + TAG_SIZE_BYTES // 60 bytes
    const val ENVELOPE_HEX_LENGTH = ENVELOPE_RAW_BYTES * 2 // 120 hex chars

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
    ): String = wrapKey(masterKey.keyBytes, kek, random)

    /**
     * Wraps a raw 32-byte [rawKey] (such as a file DEK) under [kek] into a 120-character hex string.
     */
    fun wrapKey(
        rawKey: ByteArray,
        kek: ByteArray,
        random: SecureRandom = SecureRandom()
    ): String {
        require(rawKey.size == KEY_SIZE_BYTES) { "rawKey must be exactly $KEY_SIZE_BYTES bytes" }
        require(kek.size == KEY_SIZE_BYTES) { "KEK must be exactly $KEY_SIZE_BYTES bytes" }

        val iv = ByteArray(IV_SIZE_BYTES)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(kek, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val cipherTextAndTag = cipher.doFinal(rawKey)

        val envelopeBytes = ByteArray(ENVELOPE_RAW_BYTES)
        System.arraycopy(iv, 0, envelopeBytes, 0, IV_SIZE_BYTES)
        System.arraycopy(cipherTextAndTag, 0, envelopeBytes, IV_SIZE_BYTES, cipherTextAndTag.size)

        return bytesToHex(envelopeBytes)
    }

    fun unwrap(envelopeHex: String, kek: ByteArray): MasterVaultKey {
        val decryptedKeyBytes = unwrapKey(envelopeHex, kek)
        return MasterVaultKey(decryptedKeyBytes)
    }

    fun unwrapKey(envelopeHex: String, kek: ByteArray): ByteArray {
        require(envelopeHex.length == ENVELOPE_HEX_LENGTH) {
            "Envelope hex must be exactly $ENVELOPE_HEX_LENGTH, got ${envelopeHex.length}"
        }
        require(kek.size == KEY_SIZE_BYTES) {
            "KEK must be exactly $KEY_SIZE_BYTES bytes"
        }

        val envelopeByte = hexToBytes(envelopeHex)
        val iv = envelopeByte.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertextAndTag = envelopeByte.copyOfRange(IV_SIZE_BYTES, ENVELOPE_RAW_BYTES)

        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(kek, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(ciphertextAndTag)
    }

    /**
     * Encrypts arbitrary [plaintext] bytes with [key] (32 bytes) via AES-256-GCM.
     * Wire format: [12-byte IV][ciphertext + 16-byte GCM tag]
     */
    fun encrypt(
        plaintext: ByteArray,
        key: ByteArray,
        random: SecureRandom = SecureRandom()
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "Key must be exactly $KEY_SIZE_BYTES bytes" }

        val iv = ByteArray(IV_SIZE_BYTES)
        random.nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val ciphertextAndTag = cipher.doFinal(plaintext)

        val result = ByteArray(IV_SIZE_BYTES + ciphertextAndTag.size)
        System.arraycopy(iv, 0, result, 0, IV_SIZE_BYTES)
        System.arraycopy(ciphertextAndTag, 0, result, IV_SIZE_BYTES, ciphertextAndTag.size)
        return result
    }

    /**
     * Decrypts [envelope] bytes ([12-byte IV][ciphertext + 16-byte GCM tag]) with [key] (32 bytes).
     */
    fun decrypt(
        envelope: ByteArray,
        key: ByteArray
    ): ByteArray {
        require(key.size == KEY_SIZE_BYTES) { "Key must be exactly $KEY_SIZE_BYTES bytes" }
        require(envelope.size >= IV_SIZE_BYTES + TAG_SIZE_BYTES) {
            "Envelope must be at least ${IV_SIZE_BYTES + TAG_SIZE_BYTES} bytes, got ${envelope.size}"
        }

        val iv = envelope.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertextAndTag = envelope.copyOfRange(IV_SIZE_BYTES, envelope.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(ciphertextAndTag)
    }

    fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val index = i * 2
            bytes[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return bytes
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}