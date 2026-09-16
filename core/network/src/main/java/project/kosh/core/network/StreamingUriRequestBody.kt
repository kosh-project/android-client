package project.kosh.core.network

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import kotlin.also

class StreamingUriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri
) : RequestBody() {

    override fun contentType(): MediaType? = "application/octet-stream".toMediaTypeOrNull()

    override fun contentLength(): Long {
        contentResolver
            .query(uri, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    return cursor.getLong(sizeIndex)
                }
        }
        return -1L
    }

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