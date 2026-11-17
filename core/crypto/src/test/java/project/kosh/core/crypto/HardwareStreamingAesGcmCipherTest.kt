package project.kosh.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HardwareStreamingAesGcmCipherTest {

    private val random = SecureRandom(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

    private fun generateRandomBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }

    private fun encryptWithStandardJce(
        plaintext: ByteArray,
        key: ByteArray,
        iv: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(plaintext)
    }

    private fun assertEquivalenceWithStandardJce(
        payloadSize: Int,
        chunkSize: Int = 4096
    ) {
        val key = generateRandomBytes(32)
        val iv = generateRandomBytes(12)
        val plaintext = generateRandomBytes(payloadSize)

        // 1. Standard JCE reference encryption
        val expectedCiphertextAndTag = encryptWithStandardJce(plaintext, key, iv)

        // 2. HardwareStreamingAesGcmCipher encryption
        val streamingCipher = HardwareStreamingAesGcmCipher(key, iv)
        val actualCiphertext = java.io.ByteArrayOutputStream()

        var offset = 0
        while (offset < plaintext.size) {
            val bytesToRead = minOf(chunkSize, plaintext.size - offset)
            val chunk = streamingCipher.update(plaintext, offset, bytesToRead)
            if (chunk.isNotEmpty()) {
                actualCiphertext.write(chunk)
            }
            offset += bytesToRead
        }

        val finalBytes = streamingCipher.doFinal()
        if (finalBytes.isNotEmpty()) {
            actualCiphertext.write(finalBytes)
        }

        val actualCiphertextAndTag = actualCiphertext.toByteArray()

        // 3. Exact bit-for-bit equivalence assertion
        assertEquals(
            "Encrypted size mismatch for payload size $payloadSize",
            expectedCiphertextAndTag.size,
            actualCiphertextAndTag.size
        )
        assertArrayEquals(
            "Bit-for-bit ciphertext + tag mismatch for payload size $payloadSize",
            expectedCiphertextAndTag,
            actualCiphertextAndTag
        )

        // 4. Decrypt via AesGcmEnvelope.decrypt([12B IV][ciphertext + 16B Tag])
        val wireEnvelope = ByteArray(12 + actualCiphertextAndTag.size)
        System.arraycopy(iv, 0, wireEnvelope, 0, 12)
        System.arraycopy(actualCiphertextAndTag, 0, wireEnvelope, 12, actualCiphertextAndTag.size)

        val decrypted = AesGcmEnvelope.decrypt(wireEnvelope, key)
        assertArrayEquals(
            "Decrypted plaintext mismatch for payload size $payloadSize",
            plaintext,
            decrypted
        )
    }

    @Test
    fun testEquivalence_emptyPayload_0Bytes() {
        assertEquivalenceWithStandardJce(0)
    }

    @Test
    fun testEquivalence_subBlock_15Bytes() {
        assertEquivalenceWithStandardJce(15, chunkSize = 8)
    }

    @Test
    fun testEquivalence_exactBlock_16Bytes() {
        assertEquivalenceWithStandardJce(16, chunkSize = 16)
    }

    @Test
    fun testEquivalence_unalignedBlock_17Bytes() {
        assertEquivalenceWithStandardJce(17, chunkSize = 5)
    }

    @Test
    fun testEquivalence_multiBlock_1024Bytes() {
        assertEquivalenceWithStandardJce(1024, chunkSize = 128)
    }

    @Test
    fun testEquivalence_exactChunk_64KiB() {
        assertEquivalenceWithStandardJce(64 * 1024, chunkSize = 16 * 1024)
    }

    @Test
    fun testEquivalence_multiMegabyteStream_2MB() {
        assertEquivalenceWithStandardJce(2 * 1024 * 1024, chunkSize = 64 * 1024)
    }

    @Test
    fun testSingleByteStreaming_exactEquivalence() {
        // Feed 1 byte at a time to rigorously test GHASH remainder buffering
        val key = generateRandomBytes(32)
        val iv = generateRandomBytes(12)
        val plaintext = generateRandomBytes(100)

        val expected = encryptWithStandardJce(plaintext, key, iv)

        val streamingCipher = HardwareStreamingAesGcmCipher(key, iv)
        val output = java.io.ByteArrayOutputStream()
        for (b in plaintext) {
            val chunk = streamingCipher.update(byteArrayOf(b), 0, 1)
            output.write(chunk)
        }
        output.write(streamingCipher.doFinal())

        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun testPrimeChunkSizes_exactEquivalence() {
        val key = generateRandomBytes(32)
        val iv = generateRandomBytes(12)
        val plaintext = generateRandomBytes(5000)

        val expected = encryptWithStandardJce(plaintext, key, iv)

        val streamingCipher = HardwareStreamingAesGcmCipher(key, iv)
        val output = java.io.ByteArrayOutputStream()

        var offset = 0
        val primeChunks = intArrayOf(7, 13, 23, 37, 53, 97)
        var chunkIdx = 0
        while (offset < plaintext.size) {
            val chunkSize = primeChunks[chunkIdx % primeChunks.size]
            val count = minOf(chunkSize, plaintext.size - offset)
            val chunk = streamingCipher.update(plaintext, offset, count)
            output.write(chunk)
            offset += count
            chunkIdx++
        }
        output.write(streamingCipher.doFinal())

        assertArrayEquals(expected, output.toByteArray())
    }

    @Test
    fun testConvenienceDoFinal_matchesSingleCall() {
        val key = generateRandomBytes(32)
        val iv = generateRandomBytes(12)
        val plaintext = generateRandomBytes(256)

        val expected = encryptWithStandardJce(plaintext, key, iv)

        val streamingCipher = HardwareStreamingAesGcmCipher(key, iv)
        val actual = streamingCipher.doFinal(plaintext)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun testCannotUpdateOrDoFinalAfterFinalize() {
        val key = generateRandomBytes(32)
        val iv = generateRandomBytes(12)
        val streamingCipher = HardwareStreamingAesGcmCipher(key, iv)

        streamingCipher.doFinal()

        assertThrows(IllegalStateException::class.java) {
            streamingCipher.update(ByteArray(10))
        }

        assertThrows(IllegalStateException::class.java) {
            streamingCipher.doFinal()
        }
    }

    @Test
    fun testInvalidKeyOrIvLength() {
        assertThrows(IllegalArgumentException::class.java) {
            HardwareStreamingAesGcmCipher(ByteArray(16), ByteArray(12))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareStreamingAesGcmCipher(ByteArray(32), ByteArray(16))
        }
    }

    @Test
    fun testGhashAccumulator_emptyFinalize() {
        val h = generateRandomBytes(16)
        val t0 = generateRandomBytes(16)

        val ghash = GhashAccumulator(h)
        val tag = ghash.finalizeTag(t0)

        // For empty data, len block is (0, 0), so Y1 = (0 ^ 0) * H = 0.
        // Tag = 0 ^ T0 = T0.
        assertArrayEquals(t0, tag)
    }

    @Test
    fun testOffsetSlicingWithDefaultLength() {
        val key = generateRandomBytes(32)
        val iv = generateRandomBytes(12)
        val fullBuffer = generateRandomBytes(100)
        val sliceOffset = 30
        val sliceLength = fullBuffer.size - sliceOffset
        val slicePlaintext = fullBuffer.copyOfRange(sliceOffset, fullBuffer.size)

        val expected = encryptWithStandardJce(slicePlaintext, key, iv)

        val cipher = HardwareStreamingAesGcmCipher(key, iv)
        val actual = cipher.doFinal(fullBuffer, offset = sliceOffset)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun testUpdateWithOffsetDefaultLength() {
        val key = generateRandomBytes(32)
        val iv = generateRandomBytes(12)
        val fullBuffer = generateRandomBytes(100)
        val sliceOffset = 25
        val slicePlaintext = fullBuffer.copyOfRange(sliceOffset, fullBuffer.size)

        val expected = encryptWithStandardJce(slicePlaintext, key, iv)

        val cipher = HardwareStreamingAesGcmCipher(key, iv)
        val ct = cipher.update(fullBuffer, offset = sliceOffset)
        val tag = cipher.doFinal()

        val actual = ByteArray(ct.size + tag.size)
        System.arraycopy(ct, 0, actual, 0, ct.size)
        System.arraycopy(tag, 0, actual, ct.size, tag.size)

        assertArrayEquals(expected, actual)
    }

    @Test
    fun testBoundaryValidation_throwsIllegalArgumentException() {
        val key = generateRandomBytes(32)
        val iv = generateRandomBytes(12)
        val cipher = HardwareStreamingAesGcmCipher(key, iv)
        val buffer = ByteArray(20)

        // Negative offset
        assertThrows(IllegalArgumentException::class.java) {
            cipher.update(buffer, -1, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cipher.doFinal(buffer, -1, 10)
        }

        // Negative length
        assertThrows(IllegalArgumentException::class.java) {
            cipher.update(buffer, 0, -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cipher.doFinal(buffer, 0, -1)
        }

        // Offset + length exceeds buffer size
        assertThrows(IllegalArgumentException::class.java) {
            cipher.update(buffer, 15, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            cipher.doFinal(buffer, 15, 10)
        }
    }

    @Test
    fun testGhashAccumulator_boundaryValidationAndLifecycleGuards() {
        val h = generateRandomBytes(16)
        val t0 = generateRandomBytes(16)
        val ghash = GhashAccumulator(h)
        val buffer = ByteArray(32)

        // Negative offset
        assertThrows(IllegalArgumentException::class.java) {
            ghash.update(buffer, -1, 10)
        }

        // Negative length
        assertThrows(IllegalArgumentException::class.java) {
            ghash.update(buffer, 0, -1)
        }

        // Out of bounds
        assertThrows(IllegalArgumentException::class.java) {
            ghash.update(buffer, 25, 10)
        }

        // Valid offset with default length
        ghash.update(buffer, 16) // processes remaining 16 bytes

        // Finalize tag with invalid size
        assertThrows(IllegalArgumentException::class.java) {
            ghash.finalizeTag(ByteArray(15))
        }

        val tag = ghash.finalizeTag(t0)
        assertEquals(16, tag.size)

        // Multiple finalize calls should fail
        assertThrows(IllegalStateException::class.java) {
            ghash.finalizeTag(t0)
        }

        // Update after finalize should fail
        assertThrows(IllegalStateException::class.java) {
            ghash.update(buffer, 0, 16)
        }
    }
}
