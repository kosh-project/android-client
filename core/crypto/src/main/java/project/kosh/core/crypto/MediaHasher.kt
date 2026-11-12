package project.kosh.core.crypto

import java.io.InputStream
import java.security.MessageDigest

/**
 * Hardware-accelerated streaming SHA-256 hasher for local pre-upload deduplication.
 *
 * Utilizes native Conscrypt / ARMv8 NEON cryptographic extensions via [MessageDigest]
 * in 64 KiB chunks, avoiding JNI overhead and heap memory exhaustion.
 */
object MediaHasher {
    const val BUFFER_SIZE = 64 * 1024 // 64 KiB buffer

    /**
     * Computes lowercase hex SHA-256 over an [InputStream] with optional progress reporting.
     */
    fun computeSha256(
        inputStream: InputStream,
        totalBytes: Long = -1L,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): String = computeSha256WithLength(inputStream, totalBytes, onProgress).first

    /**
     * Computes lowercase hex SHA-256 over an [InputStream] with optional progress reporting,
     * returning both the hex digest and the exact number of bytes read.
     */
    fun computeSha256WithLength(
        inputStream: InputStream,
        totalBytes: Long = -1L,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null
    ): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesRead: Int
        var totalRead = 0L
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
            totalRead += bytesRead
            onProgress?.invoke(totalRead, totalBytes)
        }
        return Pair(bytesToHex(digest.digest()), totalRead)
    }

    /**
     * Computes lowercase hex SHA-256 over a raw [ByteArray].
     */
    fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return bytesToHex(digest.digest(bytes))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
