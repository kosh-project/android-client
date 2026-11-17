package project.kosh.core.crypto

/**
 * High-performance, zero-allocation Galois Field GF(2^128) GHASH accumulator
 * adhering strictly to NIST SP 800-38D specifications for AES-GCM.
 */
class GhashAccumulator(hBytes: ByteArray) {
    init {
        require(hBytes.size == 16) { "H subkey must be exactly 16 bytes, got ${hBytes.size}" }
    }

    private val hHi: Long = readLongBigEndian(hBytes, 0)
    private val hLo: Long = readLongBigEndian(hBytes, 8)

    private var yHi: Long = 0L
    private var yLo: Long = 0L

    private val remainder = ByteArray(16)
    private var remainderLen = 0
    private var totalBytesProcessed = 0L

    private var isFinalized = false

    /**
     * Incremental accumulator for ciphertext blocks.
     * Buffers unaligned bytes and processes full 16-byte blocks with zero heap allocation.
     */
    fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        check(!isFinalized) { "Accumulator has already been finalized" }
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "Offset $offset and length $length out of bounds for buffer size ${data.size}"
        }
        if (length == 0) return

        var pos = offset
        var remaining = length
        totalBytesProcessed += length

        // If there is residual data from previous chunk, fill the 16-byte block first
        if (remainderLen > 0) {
            val toCopy = minOf(16 - remainderLen, remaining)
            System.arraycopy(data, pos, remainder, remainderLen, toCopy)
            remainderLen += toCopy
            pos += toCopy
            remaining -= toCopy

            if (remainderLen == 16) {
                processBlock(remainder, 0)
                remainderLen = 0
            }
        }

        // Process full 16-byte aligned blocks directly from input buffer without copying
        while (remaining >= 16) {
            processBlock(data, pos)
            pos += 16
            remaining -= 16
        }

        // Buffer residual bytes (< 16 bytes)
        if (remaining > 0) {
            System.arraycopy(data, pos, remainder, 0, remaining)
            remainderLen = remaining
        }
    }

    private fun processBlock(block: ByteArray, offset: Int) {
        val bHi = readLongBigEndian(block, offset)
        val bLo = readLongBigEndian(block, offset + 8)
        processBlockDirect(bHi, bLo)
    }

    private fun processBlockDirect(bHi: Long, bLo: Long) {
        yHi = yHi xor bHi
        yLo = yLo xor bLo
        multiplyYByH()
    }

    private fun multiplyYByH() {
        var zHi = 0L
        var zLo = 0L
        var vHi = hHi
        var vLo = hLo
        val rHigh = -0x1f00000000000000L // 0xE100000000000000L signed Long

        // 64 iterations for upper 64 bits of Y
        for (i in 0 until 64) {
            if (((yHi ushr (63 - i)) and 1L) != 0L) {
                zHi = zHi xor vHi
                zLo = zLo xor vLo
            }
            val lsb = (vLo and 1L) != 0L
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = (vHi ushr 1) xor (if (lsb) rHigh else 0L)
        }

        // 64 iterations for lower 64 bits of Y
        for (i in 0 until 64) {
            if (((yLo ushr (63 - i)) and 1L) != 0L) {
                zHi = zHi xor vHi
                zLo = zLo xor vLo
            }
            val lsb = (vLo and 1L) != 0L
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = (vHi ushr 1) xor (if (lsb) rHigh else 0L)
        }

        yHi = zHi
        yLo = zLo
    }

    /**
     * Finalizes the GHASH polynomial authentication tag.
     * Zero-pads remainder, appends the NIST length block (64-bit 0 || 64-bit len(C) bits),
     * and XORs with T0.
     */
    fun finalizeTag(t0Bytes: ByteArray): ByteArray {
        check(!isFinalized) { "Accumulator has already been finalized" }
        require(t0Bytes.size == 16) { "T0 must be exactly 16 bytes, got ${t0Bytes.size}" }
        isFinalized = true

        // Zero-pad any partial block
        if (remainderLen > 0) {
            for (i in remainderLen until 16) {
                remainder[i] = 0
            }
            processBlock(remainder, 0)
            remainderLen = 0
        }

        // Length block: len(A) bits (64-bit = 0) || len(C) bits (64-bit = totalBytesProcessed * 8)
        processBlockDirect(0L, totalBytesProcessed * 8L)

        // Tag = S xor T0
        val t0Hi = readLongBigEndian(t0Bytes, 0)
        val t0Lo = readLongBigEndian(t0Bytes, 8)

        val tagHi = yHi xor t0Hi
        val tagLo = yLo xor t0Lo

        val tag = ByteArray(16)
        writeLongBigEndian(tag, 0, tagHi)
        writeLongBigEndian(tag, 8, tagLo)
        return tag
    }

    companion object {
        internal fun readLongBigEndian(bytes: ByteArray, offset: Int): Long {
            return ((bytes[offset].toLong() and 0xFFL) shl 56) or
                    ((bytes[offset + 1].toLong() and 0xFFL) shl 48) or
                    ((bytes[offset + 2].toLong() and 0xFFL) shl 40) or
                    ((bytes[offset + 3].toLong() and 0xFFL) shl 32) or
                    ((bytes[offset + 4].toLong() and 0xFFL) shl 24) or
                    ((bytes[offset + 5].toLong() and 0xFFL) shl 16) or
                    ((bytes[offset + 6].toLong() and 0xFFL) shl 8) or
                    (bytes[offset + 7].toLong() and 0xFFL)
        }

        internal fun writeLongBigEndian(bytes: ByteArray, offset: Int, value: Long) {
            bytes[offset] = (value ushr 56).toByte()
            bytes[offset + 1] = (value ushr 48).toByte()
            bytes[offset + 2] = (value ushr 40).toByte()
            bytes[offset + 3] = (value ushr 32).toByte()
            bytes[offset + 4] = (value ushr 24).toByte()
            bytes[offset + 5] = (value ushr 16).toByte()
            bytes[offset + 6] = (value ushr 8).toByte()
            bytes[offset + 7] = value.toByte()
        }
    }
}
