package project.kosh.core.network

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import kotlin.also
import kotlin.math.exp

/**
 * Custom OkHttp [RequestBody] providing zero-copy streaming of binary assets directly
 * from an Android content [Uri] via [ContentResolver].
 *
 * ### Backend Hardware Realities & Mandatory Content-Length
 * The Kosh Rust/Axum server host operates on spinning mechanical hard drives (HDDs). To prevent severe
 * disk fragmentation and random I/O thrashing during large CAS uploads, the backend pre-allocates contiguous
 * disk space using `fallocate(2)` via Tokio before writing data blocks.
 *
 * Consequently, the server strictly mandates an exact `Content-Length` header on every upload request.
 * Any request that omits this header or falls back to chunked transfer encoding (`Transfer-Encoding: chunked`)
 * is immediately rejected with HTTP 400 Bad Request (`BadRequest("Missing content length in header")`).
 * This class queries [OpenableColumns.SIZE] from the Android system provider to declare the exact file length.
 *
 * ### Zero-Copy Streaming Invariant
 * To ensure that media uploads never exhaust the Android process heap, this body avoids buffering whole
 * files into memory. Bytes are streamed in 64 KiB chunks (`65,536` bytes) directly from the
 * [ContentResolver] input stream into Okio's [BufferedSink].
 *
 * @param contentResolver Android [ContentResolver] used to query file metadata and open the input stream.
 * @param uri The content [Uri] pointing to the local media asset.
 */
class StreamingUriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val explicitLength: Long? = null
) : RequestBody() {

    /**
     * Declares the payload MIME type as an opaque binary stream (`application/octet-stream`).
     *
     * @return The parsed [MediaType] for octet streams.
     */
    override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()

    /**
     * Resolves the exact byte length of the underlying asset by querying [OpenableColumns.SIZE].
     *
     * An accurate non-negative length is required so OkHttp populates the `Content-Length` header,
     * satisfying the server's `fallocate(2)` pre-allocation requirements.
     *
     * @return The file size in bytes, or `-1L` if the size cannot be determined by the system provider.
     */
    override fun contentLength(): Long {
        if (explicitLength != null && explicitLength >= 0L) return explicitLength
        contentResolver
            .query(uri, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    return cursor.getLong(sizeIndex)
                }
        }
        return -1L
    }

    /**
     * Streams the content URI bytes into the destination [BufferedSink] using a 64 KiB buffer.
     *
     * @param sink The Okio sink connected to the outgoing HTTP socket.
     */
    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(64 * 1024)

        contentResolver.openInputStream(uri)?.use { inputStream ->
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                sink.write(buffer, 0, bytesRead)
            }
        }
    }
}