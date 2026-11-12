package project.kosh.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException

class AesGcmEnvelopeTest {

    @Test
    fun wrap_producesExactly120HexCharacters() {
        val masterKey = MasterVaultKey.generate()
        val kek = ByteArray(32) { it.toByte() }

        val envelopeHex = AesGcmEnvelope.wrap(masterKey, kek)

        assertEquals("Envelope must be exactly 120 hex characters (60 raw bytes)", 120, envelopeHex.length)
        assert(envelopeHex.matches(Regex("^[0-9a-f]{120}$")))
    }

    @Test
    fun wrapAndUnwrap_roundTrip_recoversOriginalKey() {
        val originalKey = MasterVaultKey.generate()
        val kek = ByteArray(32) { (it * 3).toByte() }

        val envelopeHex = AesGcmEnvelope.wrap(originalKey, kek)
        val unwrappedKey = AesGcmEnvelope.unwrap(envelopeHex, kek)

        assertArrayEquals(originalKey.keyBytes, unwrappedKey.keyBytes)
    }

    @Test
    fun unwrap_withWrongKek_failsAuthentication() {
        val originalKey = MasterVaultKey.generate()
        val correctKek = ByteArray(32) { 1 }
        val wrongKek = ByteArray(32) { 2 }

        val envelopeHex = AesGcmEnvelope.wrap(originalKey, correctKek)

        assertThrows(GeneralSecurityException::class.java) {
            AesGcmEnvelope.unwrap(envelopeHex, wrongKek)
        }
    }

    @Test
    fun unwrap_withTamperedCiphertext_failsAuthentication() {
        val originalKey = MasterVaultKey.generate()
        val kek = ByteArray(32) { 7 }

        val envelopeHex = AesGcmEnvelope.wrap(originalKey, kek)

        // Flip one hex character in the ciphertext portion
        val tamperedHex = envelopeHex.substring(0, 30) + (if (envelopeHex[30] == 'a') 'b' else 'a') + envelopeHex.substring(31)

        assertThrows(GeneralSecurityException::class.java) {
            AesGcmEnvelope.unwrap(tamperedHex, kek)
        }
    }

    @Test
    fun encryptStream_producesValidEnvelopeDecryptableByStandardDecrypt() {
        val dek = ByteArray(32) { (it * 7).toByte() }
        val testData = ByteArray(150 * 1024) { (it % 251).toByte() }

        val inputStream = java.io.ByteArrayInputStream(testData)
        val outputStream = java.io.ByteArrayOutputStream()

        val progressReports = mutableListOf<Long>()
        val totalWritten = AesGcmEnvelope.encryptStream(
            inputStream = inputStream,
            outputStream = outputStream,
            key = dek,
            bufferSize = 32 * 1024,
            onProgress = { progressReports.add(it) }
        )

        val encryptedBytes = outputStream.toByteArray()
        assertEquals(totalWritten, encryptedBytes.size.toLong())
        assertEquals((testData.size + AesGcmEnvelope.IV_SIZE_BYTES + AesGcmEnvelope.TAG_SIZE_BYTES).toLong(), totalWritten)

        // Decrypt using standard decrypt and verify match
        val decrypted = AesGcmEnvelope.decrypt(encryptedBytes, dek)
        assertArrayEquals(testData, decrypted)

        // Verify progress callbacks fired
        assert(progressReports.isNotEmpty())
        assertEquals(testData.size.toLong(), progressReports.last())
    }

    @Test
    fun encryptStream_emptyStream_producesValidEnvelope() {
        val dek = ByteArray(32) { 5 }
        val inputStream = java.io.ByteArrayInputStream(ByteArray(0))
        val outputStream = java.io.ByteArrayOutputStream()

        val totalWritten = AesGcmEnvelope.encryptStream(
            inputStream = inputStream,
            outputStream = outputStream,
            key = dek
        )

        assertEquals((AesGcmEnvelope.IV_SIZE_BYTES + AesGcmEnvelope.TAG_SIZE_BYTES).toLong(), totalWritten)
        val decrypted = AesGcmEnvelope.decrypt(outputStream.toByteArray(), dek)
        assertEquals(0, decrypted.size)
    }
}

