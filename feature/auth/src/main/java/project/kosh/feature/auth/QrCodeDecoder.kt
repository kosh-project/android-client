package project.kosh.feature.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Pure decoder utility for QR codes with native inverted-luminance support.
 *
 * Server pairing QR codes rendered in terminal TUIs use white Unicode half-blocks on black
 * terminal backgrounds (inverted luminance) with zero quiet zone.
 *
 * Google Code Scanner and standard binarizers fail completely on inverted QR codes because
 * QR specification finder patterns (1:1:3:1:1 ratio) expect dark modules on light backgrounds.
 *
 * This decoder first attempts standard decoding, and if it fails (e.g. NotFoundException),
 * IMMEDIATELY attempts decoding via `source.invert()`. The invert call flips luminance bytes
 * in-place/lazily so white terminal modules become black and black background becomes white,
 * decoding inverted terminal QR codes in a single frame (< 16ms).
 */
object QrCodeDecoder {
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)
    )

    fun decode(source: LuminanceSource): Result? {
        val reader = QRCodeReader()

        // 1. First attempt: standard decoding (black on white)
        try {
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            return reader.decode(bitmap, hints)
        } catch (_: NotFoundException) {
            // Expected for inverted terminal QR codes
        } catch (_: ChecksumException) {
            // Corrupt or partial standard frame
        } catch (_: FormatException) {
            // Malformed standard frame
        } finally {
            reader.reset()
        }

        // 2. Second attempt: inverted decoding (white on black terminal)
        return try {
            val invertedBitmap = BinaryBitmap(HybridBinarizer(source.invert()))
            reader.decode(invertedBitmap, hints)
        } catch (_: NotFoundException) {
            null
        } catch (_: ChecksumException) {
            null
        } catch (_: FormatException) {
            null
        } finally {
            reader.reset()
        }
    }

    /**
     * Extracts the true binary payload bytes from a ZXing [Result].
     *
     * In QR Code Byte Mode, ZXing stores the decoded byte arrays inside
     * [ResultMetadataType.BYTE_SEGMENTS]. ZXing's [Result.getRawBytes] contains the
     * raw Reed-Solomon data codewords which are bit-shifted by the 12-bit Mode and
     * Length header and does not match the raw payload directly.
     */
    fun extractPayloadBytes(result: Result): ByteArray? {
        @Suppress("UNCHECKED_CAST")
        val byteSegments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
        if (!byteSegments.isNullOrEmpty()) {
            if (byteSegments.size == 1) return byteSegments[0]
            val total = byteSegments.sumOf { it.size }
            val combined = ByteArray(total)
            var offset = 0
            for (seg in byteSegments) {
                System.arraycopy(seg, 0, combined, offset, seg.size)
                offset += seg.size
            }
            return combined
        }

        // Fallback: If 12-bit mode/length header is present in raw codewords (e.g. 34-byte array for 32-byte payload)
        val raw = result.rawBytes
        if (raw != null && raw.size >= 34) {
            val mode = (raw[0].toInt() and 0xF0) ushr 4
            // Mode 4 = Byte mode in QR spec
            if (mode == 4) {
                val len = ((raw[0].toInt() and 0x0F) shl 4) or ((raw[1].toInt() and 0xF0) ushr 4)
                if (len in 32..raw.size - 2) {
                    val unpacked = ByteArray(len) { i ->
                        val high = (raw[1 + i].toInt() and 0x0F) shl 4
                        val low = (raw[2 + i].toInt() and 0xF0) ushr 4
                        (high or low).toByte()
                    }
                    return unpacked
                }
            }
        }

        return result.rawBytes
    }
}
