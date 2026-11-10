package project.kosh.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Arrays

class LedgerCryptoTest {

    @Test
    fun deriveLedgerKey_derivesDeterministic32ByteKey() {
        val rawMasterKey = ByteArray(32) { it.toByte() }
        val masterKey1 = MasterVaultKey(rawMasterKey.clone())
        val masterKey2 = MasterVaultKey(rawMasterKey.clone())

        val ledgerKey1 = LedgerCrypto.deriveLedgerKey(masterKey1)
        val ledgerKey2 = LedgerCrypto.deriveLedgerKey(masterKey2)

        assertEquals(32, ledgerKey1.size)
        assertEquals(32, ledgerKey2.size)
        assertTrue(Arrays.equals(ledgerKey1, ledgerKey2))

        // Different master key must produce different ledger key
        val differentMaster = MasterVaultKey(ByteArray(32) { (it + 1).toByte() })
        val differentLedgerKey = LedgerCrypto.deriveLedgerKey(differentMaster)
        assertFalse(Arrays.equals(ledgerKey1, differentLedgerKey))
    }

    @Test
    fun encryptAndDecryptBundle_roundTrip() {
        val ledgerKey = ByteArray(32)
        SecureRandom().nextBytes(ledgerKey)

        val plaintextPayload = "Hello Delta-CRDT Protobuf Bundle Payload!".toByteArray(Charsets.UTF_8)
        val ciphertext = LedgerCrypto.encryptBundle(plaintextPayload, ledgerKey)

        // Envelope must have 12-byte IV + ciphertext + 16-byte GCM tag
        assertEquals(12 + plaintextPayload.size + 16, ciphertext.size)

        val decrypted = LedgerCrypto.decryptBundle(ciphertext, ledgerKey)
        assertTrue(Arrays.equals(plaintextPayload, decrypted))
        assertEquals("Hello Delta-CRDT Protobuf Bundle Payload!", String(decrypted, Charsets.UTF_8))
    }

    @Test(expected = Exception::class)
    fun decryptBundle_withWrongKey_failsAuthentication() {
        val ledgerKey = ByteArray(32) { 1.toByte() }
        val wrongKey = ByteArray(32) { 2.toByte() }

        val plaintext = "Sensitive Bundle".toByteArray()
        val ciphertext = LedgerCrypto.encryptBundle(plaintext, ledgerKey)

        LedgerCrypto.decryptBundle(ciphertext, wrongKey)
    }

    @Test
    fun aesGcmEnvelope_wrapKeyAndUnwrapKey_roundTrip() {
        val dek = ByteArray(32)
        SecureRandom().nextBytes(dek)
        val masterKey = ByteArray(32)
        SecureRandom().nextBytes(masterKey)

        val wrappedHex = AesGcmEnvelope.wrapKey(dek, masterKey)
        assertEquals(120, wrappedHex.length) // 60 raw bytes * 2

        val unwrappedDek = AesGcmEnvelope.unwrapKey(wrappedHex, masterKey)
        assertTrue(Arrays.equals(dek, unwrappedDek))
    }
}
