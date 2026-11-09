package project.kosh.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class EcdhPairingCryptoTest {

    @Test
    fun rfc7748_section6_1_aliceAndBobTestVector() {
        val basePoint = ByteArray(32).apply { this[0] = 9 }

        val alicePriv = EcdhPairingCrypto.hexToBytes("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePubExpected = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        val alicePub = EcdhPairingCrypto.bytesToHex(EcdhPairingCrypto.scalarMult(alicePriv, basePoint))
        assertEquals(alicePubExpected, alicePub)

        val bobPriv = EcdhPairingCrypto.hexToBytes("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPubExpected = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        val bobPub = EcdhPairingCrypto.bytesToHex(EcdhPairingCrypto.scalarMult(bobPriv, basePoint))
        assertEquals(bobPubExpected, bobPub)

        val ssAlice = EcdhPairingCrypto.bytesToHex(EcdhPairingCrypto.computeSharedSecret(alicePriv, EcdhPairingCrypto.hexToBytes(bobPub)))
        val ssBob = EcdhPairingCrypto.bytesToHex(EcdhPairingCrypto.computeSharedSecret(bobPriv, EcdhPairingCrypto.hexToBytes(alicePub)))
        val expectedSharedSecret = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(expectedSharedSecret, ssAlice)
        assertEquals(expectedSharedSecret, ssBob)
    }

    @Test
    fun rfc7748_section5_2_vector1() {
        val scalar = EcdhPairingCrypto.hexToBytes("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = EcdhPairingCrypto.hexToBytes("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val expected = "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552"
        val actual = EcdhPairingCrypto.bytesToHex(EcdhPairingCrypto.scalarMult(scalar, u))
        assertEquals(expected, actual)
    }

    @Test
    fun rfc7748_section5_2_vector2() {
        val scalar = EcdhPairingCrypto.hexToBytes("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d")
        val u = EcdhPairingCrypto.hexToBytes("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")
        val expected = "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957"
        val actual = EcdhPairingCrypto.bytesToHex(EcdhPairingCrypto.scalarMult(scalar, u))
        assertEquals(expected, actual)
    }

    @Test
    fun computeSharedSecret_rejectsSmallOrderAllZeroPoint() {
        val priv = ByteArray(32).apply { this[0] = 42 }
        val allZeroPub = ByteArray(32) // Point of small order
        assertThrows(IllegalArgumentException::class.java) {
            EcdhPairingCrypto.computeSharedSecret(priv, allZeroPub)
        }
    }

    @Test
    fun e2eePayload_fullRoundTripEncryptionAndDecryption() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val originalMasterKey = MasterVaultKey.generate()

        // Sender encrypts MasterVaultKey targeting receiver's public key
        val payloadHex = EcdhPairingCrypto.encryptPayload(originalMasterKey, receiverKeyPair.publicKey)
        assertEquals(184, payloadHex.length) // 92 bytes = 184 hex characters

        // Receiver decrypts using their private key
        val recoveredMasterKey = EcdhPairingCrypto.decryptPayload(payloadHex, receiverKeyPair.privateKey)
        assertArrayEquals(originalMasterKey.keyBytes, recoveredMasterKey.keyBytes)

        // Clean up
        assertFalse(receiverKeyPair.isClosed)
        receiverKeyPair.close()
        assertTrue(receiverKeyPair.isClosed)
        assertTrue(receiverKeyPair.privateKey.all { it == 0.toByte() })
    }

    @Test
    fun e2eePayload_tamperedPublicKey_failsDecryption() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val originalMasterKey = MasterVaultKey.generate()

        val payloadHex = EcdhPairingCrypto.encryptPayload(originalMasterKey, receiverKeyPair.publicKey)
        val raw = EcdhPairingCrypto.hexToBytes(payloadHex)

        // Tamper with sender's public key (first byte)
        raw[0] = (raw[0].toInt() xor 0xFF).toByte()
        val tamperedHex = EcdhPairingCrypto.bytesToHex(raw)

        assertThrows(AEADBadTagException::class.java) {
            EcdhPairingCrypto.decryptPayload(tamperedHex, receiverKeyPair.privateKey)
        }
    }

    @Test
    fun e2eePayload_tamperedNonce_failsDecryption() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val originalMasterKey = MasterVaultKey.generate()

        val payloadHex = EcdhPairingCrypto.encryptPayload(originalMasterKey, receiverKeyPair.publicKey)
        val raw = EcdhPairingCrypto.hexToBytes(payloadHex)

        // Nonce is bytes 32..43; tamper byte 33
        raw[33] = (raw[33].toInt() xor 0x01).toByte()
        val tamperedHex = EcdhPairingCrypto.bytesToHex(raw)

        assertThrows(AEADBadTagException::class.java) {
            EcdhPairingCrypto.decryptPayload(tamperedHex, receiverKeyPair.privateKey)
        }
    }

    @Test
    fun e2eePayload_tamperedCiphertext_failsDecryption() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val originalMasterKey = MasterVaultKey.generate()

        val payloadHex = EcdhPairingCrypto.encryptPayload(originalMasterKey, receiverKeyPair.publicKey)
        val raw = EcdhPairingCrypto.hexToBytes(payloadHex)

        // Ciphertext starts at byte 44
        raw[50] = (raw[50].toInt() xor 0xAA).toByte()
        val tamperedHex = EcdhPairingCrypto.bytesToHex(raw)

        assertThrows(AEADBadTagException::class.java) {
            EcdhPairingCrypto.decryptPayload(tamperedHex, receiverKeyPair.privateKey)
        }
    }

    @Test
    fun e2eePayload_tamperedTag_failsDecryption() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val originalMasterKey = MasterVaultKey.generate()

        val payloadHex = EcdhPairingCrypto.encryptPayload(originalMasterKey, receiverKeyPair.publicKey)
        val raw = EcdhPairingCrypto.hexToBytes(payloadHex)

        // Tag is the last 16 bytes (91 is the last byte)
        raw[91] = (raw[91].toInt() xor 0x55).toByte()
        val tamperedHex = EcdhPairingCrypto.bytesToHex(raw)

        assertThrows(AEADBadTagException::class.java) {
            EcdhPairingCrypto.decryptPayload(tamperedHex, receiverKeyPair.privateKey)
        }
    }

    @Test
    fun e2eePayload_invalidLength_throwsIllegalArgument() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            EcdhPairingCrypto.decryptPayload("deadbeef", receiverKeyPair.privateKey)
        }
    }

    @Test
    fun compoundPayload_fullRoundTripEncryptionAndDecryption() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val originalMasterKey = MasterVaultKey.generate()
        val ticketHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val payloadHex = EcdhPairingCrypto.encryptCompoundPayload(
            ticketHex = ticketHex,
            vaultKey = originalMasterKey,
            peerPublicKey = receiverKeyPair.publicKey
        )
        assertEquals(248, payloadHex.length) // 124 bytes = 248 hex characters

        val bundle = EcdhPairingCrypto.decryptCompoundPayload(payloadHex, receiverKeyPair.privateKey)
        assertEquals(ticketHex, bundle.registrationTicket)
        assertArrayEquals(originalMasterKey.keyBytes, bundle.vaultKey.keyBytes)

        bundle.close()
        receiverKeyPair.close()
    }

    @Test
    fun compoundPayload_backwardCompatibilityWithLegacy184HexChars() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val originalMasterKey = MasterVaultKey.generate()

        // Generate legacy 184-hex envelope
        val legacyPayloadHex = EcdhPairingCrypto.encryptPayload(originalMasterKey, receiverKeyPair.publicKey)
        assertEquals(184, legacyPayloadHex.length)

        // Decrypt using compound unpacker
        val bundle = EcdhPairingCrypto.decryptCompoundPayload(legacyPayloadHex, receiverKeyPair.privateKey)
        assertEquals("", bundle.registrationTicket)
        assertArrayEquals(originalMasterKey.keyBytes, bundle.vaultKey.keyBytes)

        bundle.close()
        receiverKeyPair.close()
    }

    @Test
    fun compoundPayload_tamperedTag_failsDecryption() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val originalMasterKey = MasterVaultKey.generate()
        val ticketHex = "a1b2c3d4e5f60123a1b2c3d4e5f60123a1b2c3d4e5f60123a1b2c3d4e5f60123"

        val payloadHex = EcdhPairingCrypto.encryptCompoundPayload(
            ticketHex = ticketHex,
            vaultKey = originalMasterKey,
            peerPublicKey = receiverKeyPair.publicKey
        )
        val raw = EcdhPairingCrypto.hexToBytes(payloadHex)

        // Tamper last byte of GCM tag
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x7F).toByte()
        val tamperedHex = EcdhPairingCrypto.bytesToHex(raw)

        assertThrows(AEADBadTagException::class.java) {
            EcdhPairingCrypto.decryptCompoundPayload(tamperedHex, receiverKeyPair.privateKey)
        }
        receiverKeyPair.close()
    }

    @Test
    fun masterVaultKey_closeZeroesKeyBytesAndSetsIsClosed() {
        val masterKey = MasterVaultKey.generate()
        assertFalse(masterKey.isClosed)
        assertTrue(masterKey.keyBytes.any { it != 0.toByte() })

        masterKey.close()
        assertTrue(masterKey.isClosed)
        assertTrue(masterKey.keyBytes.all { it == 0.toByte() })

        // Calling close again is idempotent
        masterKey.close()
        assertTrue(masterKey.isClosed)
    }

    @Test
    fun pairingBundle_closeZeroesVaultKeyAndSetsIsClosed() {
        val masterKey = MasterVaultKey.generate()
        val bundle = PairingBundle(
            registrationTicket = "0123456789abcdef".repeat(4),
            vaultKey = masterKey
        )
        assertFalse(bundle.isClosed)
        assertFalse(masterKey.isClosed)

        bundle.close()
        assertTrue(bundle.isClosed)
        assertTrue(masterKey.isClosed)
        assertTrue(masterKey.keyBytes.all { it == 0.toByte() })
    }

    @Test
    fun compoundPayload_toleratesWhitespaceAndUppercase() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val masterKey = MasterVaultKey.generate()
        val ticketHex = "0123456789abcdef".repeat(4)

        val payloadHex = EcdhPairingCrypto.encryptCompoundPayload(
            ticketHex = "  $ticketHex  ",
            vaultKey = masterKey,
            peerPublicKey = receiverKeyPair.publicKey
        )

        val uppercasePayload = "  " + payloadHex.uppercase() + "  "
        val bundle = EcdhPairingCrypto.decryptCompoundPayload(uppercasePayload, receiverKeyPair.privateKey)

        assertEquals(ticketHex, bundle.registrationTicket)
        assertArrayEquals(masterKey.keyBytes, bundle.vaultKey.keyBytes)

        bundle.close()
        receiverKeyPair.close()
    }

    @Test
    fun decryptPayload_producesKeyThatZeroesOnClose() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val masterKey = MasterVaultKey.generate()
        val payloadHex = EcdhPairingCrypto.encryptPayload(masterKey, receiverKeyPair.publicKey)

        val decrypted = EcdhPairingCrypto.decryptPayload(payloadHex, receiverKeyPair.privateKey)
        assertArrayEquals(masterKey.keyBytes, decrypted.keyBytes)
        assertFalse(decrypted.isClosed)

        decrypted.close()
        assertTrue(decrypted.isClosed)
        assertTrue(decrypted.keyBytes.all { it == 0.toByte() })

        masterKey.close()
        receiverKeyPair.close()
    }

    @Test
    fun decryptCompoundPayload_tamperedPayload_failsAndLeavesNoUncaughtExceptions() {
        val receiverKeyPair = EcdhPairingCrypto.generateKeyPair()
        val masterKey = MasterVaultKey.generate()
        val ticketHex = "0123456789abcdef".repeat(4)

        val payloadHex = EcdhPairingCrypto.encryptCompoundPayload(ticketHex, masterKey, receiverKeyPair.publicKey)
        val tamperedBytes = EcdhPairingCrypto.hexToBytes(payloadHex)
        tamperedBytes[tamperedBytes.size - 2] = (tamperedBytes[tamperedBytes.size - 2].toInt() xor 0xFF).toByte()
        val tamperedHex = EcdhPairingCrypto.bytesToHex(tamperedBytes)

        assertThrows(AEADBadTagException::class.java) {
            EcdhPairingCrypto.decryptCompoundPayload(tamperedHex, receiverKeyPair.privateKey)
        }

        masterKey.close()
        receiverKeyPair.close()
    }
}
