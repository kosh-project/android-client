package project.kosh.core.auth.fido

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class CborEncoderTest {

    private lateinit var ecPublicKey: ECPublicKey

    @Before
    fun setUp() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()
        ecPublicKey = keyPair.public as ECPublicKey
    }

    @Test
    fun `encodeCoseKey produces exact 77-byte canonical CBOR ES256 key`() {
        val coseKey = CborEncoder.encodeCoseKey(ecPublicKey)

        // Total byte length: 1 + 2 + 2 + 2 + 34 + 34 = 77 bytes
        assertEquals(77, coseKey.size)

        // Offset 0: Map of 5 entries
        assertEquals(0xa5.toByte(), coseKey[0])

        // Entry 1: 1 (kty) -> 2 (EC2)
        assertEquals(0x01.toByte(), coseKey[1])
        assertEquals(0x02.toByte(), coseKey[2])

        // Entry 2: 3 (alg) -> -7 (ES256, major type 1 negative integer: -1 - 6 = -7 -> 0x26)
        assertEquals(0x03.toByte(), coseKey[3])
        assertEquals(0x26.toByte(), coseKey[4])

        // Entry 3: -1 (crv) -> 1 (P-256, major type 1 negative integer: -1 - 0 = -1 -> 0x20)
        assertEquals(0x20.toByte(), coseKey[5])
        assertEquals(0x01.toByte(), coseKey[6])

        // Entry 4: -2 (x) -> 32-byte byte string header: 0x21, 0x58, 0x20
        assertEquals(0x21.toByte(), coseKey[7])
        assertEquals(0x58.toByte(), coseKey[8])
        assertEquals(0x20.toByte(), coseKey[9])

        // Verify X coordinate (32 bytes at offset 10..41)
        val extractedX = coseKey.copyOfRange(10, 42)
        assertEquals(32, extractedX.size)
        val expectedX = normalizeTo32Bytes(ecPublicKey.w.affineX.toByteArray())
        assertArrayEquals(expectedX, extractedX)

        // Entry 5: -3 (y) -> 32-byte byte string header: 0x22, 0x58, 0x20
        assertEquals(0x22.toByte(), coseKey[42])
        assertEquals(0x58.toByte(), coseKey[43])
        assertEquals(0x20.toByte(), coseKey[44])

        // Verify Y coordinate (32 bytes at offset 45..76)
        val extractedY = coseKey.copyOfRange(45, 77)
        assertEquals(32, extractedY.size)
        val expectedY = normalizeTo32Bytes(ecPublicKey.w.affineY.toByteArray())
        assertArrayEquals(expectedY, extractedY)
    }

    @Test
    fun `encodeAuthData for assertion creates exact 37-byte buffer with flags 0x05`() {
        val rpId = "kosh-server.local"
        val rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(Charsets.UTF_8))
        val signCount = 42

        val authData = CborEncoder.encodeAuthData(
            rpIdHash = rpIdHash,
            flags = CborEncoder.FLAGS_ASSERTION_BIOMETRIC,
            signCount = signCount
        )

        // Wire length: 32B rpIdHash + 1B flags + 4B counter = 37 bytes
        assertEquals(37, authData.size)

        // Verify rpIdHash (0..31)
        val extractedHash = authData.copyOfRange(0, 32)
        assertArrayEquals(rpIdHash, extractedHash)

        // Verify flags (32): 0x05 (UP=1, UV=1)
        assertEquals(0x05.toByte(), authData[32])
        assertEquals(CborEncoder.FLAGS_ASSERTION_BIOMETRIC, authData[32])

        // Verify signCount (33..36, big-endian)
        val extractedCount = (authData[33].toInt() and 0xff shl 24) or
                (authData[34].toInt() and 0xff shl 16) or
                (authData[35].toInt() and 0xff shl 8) or
                (authData[36].toInt() and 0xff)
        assertEquals(42, extractedCount)
    }

    @Test
    fun `encodeAttestationObject formats valid none attestation structure in canonical CBOR`() {
        val rpId = "kosh.local"
        val rpIdHash = MessageDigest.getInstance("SHA-256").digest(rpId.toByteArray(Charsets.UTF_8))
        val credId = ByteArray(32) { it.toByte() }

        val attestationBytes = CborEncoder.encodeAttestationObject(
            rpIdHash = rpIdHash,
            credentialId = credId,
            ecPublicKey = ecPublicKey,
            signCount = 0
        )

        // Expected authData size: 32 + 1 + 4 + 16 + 2 + 32 + 77 = 164 bytes
        // Expected CBOR overhead: 1 (0xa3) + 9 ("fmt":"none") + 9 ("attStmt":{}) + 11 ("authData"+0x58+len) = 30 bytes
        // Total expected size: 164 + 30 = 194 bytes
        assertEquals(194, attestationBytes.size)

        // Header: Map of 3 entries (0xa3)
        assertEquals(0xa3.toByte(), attestationBytes[0])

        // Canonical Key 1: "fmt" (len 3) -> "none" (len 4)
        assertEquals(0x63.toByte(), attestationBytes[1])
        assertEquals("fmt", String(attestationBytes.copyOfRange(2, 5), Charsets.UTF_8))
        assertEquals(0x64.toByte(), attestationBytes[5])
        assertEquals("none", String(attestationBytes.copyOfRange(6, 10), Charsets.UTF_8))

        // Canonical Key 2: "attStmt" (len 7) -> {} (0xa0)
        assertEquals(0x67.toByte(), attestationBytes[10])
        assertEquals("attStmt", String(attestationBytes.copyOfRange(11, 18), Charsets.UTF_8))
        assertEquals(0xa0.toByte(), attestationBytes[18])

        // Canonical Key 3: "authData" (len 8) -> byte string (0x58, 164)
        assertEquals(0x68.toByte(), attestationBytes[19])
        assertEquals("authData", String(attestationBytes.copyOfRange(20, 28), Charsets.UTF_8))
        assertEquals(0x58.toByte(), attestationBytes[28])
        assertEquals(164.toByte(), attestationBytes[29])

        // Inspect inner authData within attestationObject
        val authData = attestationBytes.copyOfRange(30, 30 + 164)
        assertEquals(164, authData.size)

        // Check rpIdHash
        assertArrayEquals(rpIdHash, authData.copyOfRange(0, 32))

        // Check flags: 0x45 (UP=1, UV=1, AT=1)
        assertEquals(0x45.toByte(), authData[32])

        // Check signCount: 0
        assertEquals(0, authData[33].toInt())
        assertEquals(0, authData[34].toInt())
        assertEquals(0, authData[35].toInt())
        assertEquals(0, authData[36].toInt())

        // Check AAGUID (16 zeros)
        val aaguid = authData.copyOfRange(37, 53)
        assertTrue(aaguid.all { it == 0.toByte() })

        // Check credId length (2 bytes: 0x00, 0x20 -> 32)
        assertEquals(0.toByte(), authData[53])
        assertEquals(32.toByte(), authData[54])

        // Check credId
        assertArrayEquals(credId, authData.copyOfRange(55, 87))

        // Check embedded COSE_Key (87..163, 77 bytes)
        val coseKey = authData.copyOfRange(87, 164)
        assertEquals(77, coseKey.size)
        assertEquals(0xa5.toByte(), coseKey[0])
    }

    private fun normalizeTo32Bytes(raw: ByteArray): ByteArray {
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32).apply {
                System.arraycopy(raw, 0, this, 32 - raw.size, raw.size)
            }
        }
    }
}
