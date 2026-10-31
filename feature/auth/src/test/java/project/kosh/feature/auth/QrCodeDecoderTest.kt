package project.kosh.feature.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrCodeDecoderTest {

    @Test
    fun `decode standard black-on-white QR code succeeds`() {
        val payload = "kosh:server:test-payload-1234"
        val bitMatrix = generateQrBitMatrix(payload, 200, 200)

        // Standard: black modules (0x000000) on white background (0xFFFFFF)
        val pixels = IntArray(200 * 200)
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                pixels[y * 200 + x] = if (bitMatrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        val source = RGBLuminanceSource(200, 200, pixels)

        val result = QrCodeDecoder.decode(source)
        assertNotNull(result)
        assertEquals(payload, result?.text)
    }

    @Test
    fun `decode inverted white-on-black terminal QR code succeeds via source invert`() {
        val payload = "kosh:pairing:inverted-terminal-code"
        val bitMatrix = generateQrBitMatrix(payload, 200, 200)

        // Inverted: white modules (0xFFFFFF) on black background (0x000000)
        val pixels = IntArray(200 * 200)
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                pixels[y * 200 + x] = if (bitMatrix.get(x, y)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }
        val source = RGBLuminanceSource(200, 200, pixels)

        val result = QrCodeDecoder.decode(source)
        assertNotNull("Inverted QR code must be successfully decoded via fallback", result)
        assertEquals(payload, result?.text)
    }

    @Test
    fun `decode inverted QR code rotated by 90 degrees succeeds`() {
        val payload = "kosh:rotated:inverted:90deg"
        val size = 200
        val bitMatrix = generateQrBitMatrix(payload, size, size)

        // Inverted and rotated 90 degrees clockwise:
        // (x, y) -> (size - 1 - y, x)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val isSet = bitMatrix.get(x, y)
                val rotX = size - 1 - y
                val rotY = x
                pixels[rotY * size + rotX] = if (isSet) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }
        val source = RGBLuminanceSource(size, size, pixels)

        val result = QrCodeDecoder.decode(source)
        assertNotNull("Rotated inverted QR code must be decoded", result)
        assertEquals(payload, result?.text)
    }

    @Test
    fun `decode 32-byte binary fingerprint payload in inverted terminal QR code`() {
        val fingerprint = ByteArray(32) { (it * 7).toByte() }
        // Encode binary bytes using ISO-8859-1 string (standard 8-bit byte mode)
        val isoString = String(fingerprint, Charsets.ISO_8859_1)
        val size = 200
        val bitMatrix = generateQrBitMatrix(isoString, size, size)

        // Inverted: white modules on black background
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (bitMatrix.get(x, y)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }
        val source = RGBLuminanceSource(size, size, pixels)
        val result = QrCodeDecoder.decode(source)
        assertNotNull(result)

        // Verify that extractPayloadBytes extracts the true 32-byte payload from BYTE_SEGMENTS
        val payloadBytes = QrCodeDecoder.extractPayloadBytes(result!!)
        assertNotNull("Payload bytes must be extracted from result", payloadBytes)
        assertEquals(32, payloadBytes?.size)
        org.junit.Assert.assertArrayEquals(fingerprint, payloadBytes)
    }

    @Test
    fun `extractPayloadBytes falls back to unpacking 12-bit shifted raw codewords`() {
        val fingerprint = ByteArray(32) { (it * 13).toByte() }
        // Synthesize 34-byte raw codewords with 12-bit mode (4) and length (32) header
        val rawCodewords = ByteArray(34)
        rawCodewords[0] = 0x42.toByte() // Mode 4 (Byte), high nibble of length 0x20 is 2
        rawCodewords[1] = ((0 and 0x0F) shl 4 or ((fingerprint[0].toInt() and 0xF0) ushr 4)).toByte()
        for (i in 0 until 31) {
            val high = (fingerprint[i].toInt() and 0x0F) shl 4
            val low = (fingerprint[i + 1].toInt() and 0xF0) ushr 4
            rawCodewords[2 + i] = (high or low).toByte()
        }
        rawCodewords[33] = ((fingerprint[31].toInt() and 0x0F) shl 4).toByte()

        // Create dummy Result without BYTE_SEGMENTS metadata
        val dummyResult = com.google.zxing.Result(
            "dummy",
            rawCodewords,
            emptyArray(),
            BarcodeFormat.QR_CODE
        )

        val unpacked = QrCodeDecoder.extractPayloadBytes(dummyResult)
        assertNotNull(unpacked)
        assertEquals(32, unpacked?.size)
        org.junit.Assert.assertArrayEquals(fingerprint, unpacked)
    }

    @Test
    fun `test PlanarYUVLuminanceSource with realistic rowStride buffer size`() {
        val width = 1280
        val height = 720
        val rowStride = 1344
        // In Android Camera2, last row does NOT include rowStride padding:
        val bufferSize = rowStride * (height - 1) + width
        val yBytes = ByteArray(bufferSize)

        val source = com.google.zxing.PlanarYUVLuminanceSource(
            yBytes,
            rowStride,
            height,
            0,
            0,
            width,
            height,
            false
        )
        val bitmap = BinaryBitmap(com.google.zxing.common.HybridBinarizer(source))
        val blackMatrix = bitmap.blackMatrix
        assertNotNull(blackMatrix)
    }

    @Test
    fun `decode noise or empty source returns null gracefully without exception`() {
        val pixels = IntArray(100 * 100) { 0x7F7F7F } // Solid gray noise
        val source = RGBLuminanceSource(100, 100, pixels)

        val result = QrCodeDecoder.decode(source)
        assertNull(result)
    }

    private fun generateQrBitMatrix(text: String, width: Int, height: Int): BitMatrix {
        val writer = QRCodeWriter()
        return writer.encode(text, BarcodeFormat.QR_CODE, width, height)
    }
}
