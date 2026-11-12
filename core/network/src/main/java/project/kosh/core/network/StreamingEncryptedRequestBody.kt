package project.kosh.core.network

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import project.kosh.core.crypto.AesGcmEnvelope
import java.io.IOException
import java.io.InputStream

/**
 * Custom OkHttp [RequestBody] providing on-the-fly streaming AES-256-GCM encryption
 * directly from an [InputStream] into Okio's [BufferedSink].
 *
 * Wire format: [12-byte IV][ciphertext][16-byte GCM tag].
 * Total encrypted size is exactly declared up-front:
 * `plaintextSize + IV_SIZE_BYTES (12) + TAG_SIZE_BYTES (16) = plaintextSize + 28`.
 *
 * Eliminates full-file heap allocations by streaming in 64 KiB chunks,
 * satisfying the server's mandatory Content-Length header for `fallocate(2)`.
 */
class StreamingEncryptedRequestBody(
    private val plaintextSize: Long,
    private val dek: ByteArray,
    private val inputStreamProvider: () -> InputStream?
) : RequestBody() {

    override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()

    override fun contentLength(): Long {
        if (plaintextSize < 0L) return -1L
        return plaintextSize + AesGcmEnvelope.IV_SIZE_BYTES + AesGcmEnvelope.TAG_SIZE_BYTES
    }

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val stream = inputStreamProvider() ?: throw IOException("Could not open input stream for streaming encrypted upload")
        stream.use { input ->
            AesGcmEnvelope.encryptStream(
                inputStream = input,
                outputStream = sink.outputStream(),
                key = dek
            )
        }
    }

    companion object {
        fun resolvePlaintextSize(contentResolver: ContentResolver, uri: Uri, fallback: Long = -1L): Long {
            var size = fallback
            try {
                contentResolver.query(uri, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        val s = cursor.getLong(sizeIndex)
                        if (s > 0L) size = s
                    }
                }
            } catch (_: Exception) {}
            if (size <= 0L) {
                try {
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        if (pfd.statSize > 0L) size = pfd.statSize
                    }
                } catch (_: Exception) {}
            }
            if (size <= 0L && uri.path != null) {
                try {
                    val f = java.io.File(uri.path!!)
                    if (f.exists() && f.length() > 0L) size = f.length()
                } catch (_: Exception) {}
            }
            return size
        }

        fun fromUri(
            contentResolver: ContentResolver,
            uri: Uri,
            dek: ByteArray,
            plaintextSize: Long
        ): StreamingEncryptedRequestBody {
            val resolvedSize = if (plaintextSize > 0L) {
                plaintextSize
            } else {
                resolvePlaintextSize(contentResolver, uri, plaintextSize)
            }
            return StreamingEncryptedRequestBody(
                plaintextSize = resolvedSize,
                dek = dek,
                inputStreamProvider = { contentResolver.openInputStream(uri) }
            )
        }
    }
}
