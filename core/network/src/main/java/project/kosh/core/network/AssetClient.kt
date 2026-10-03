package project.kosh.core.network

import android.R
import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import project.kosh.core.model.AssetTag
import project.kosh.core.model.NetworkResult
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network client responsible for Content-Addressable Storage (CAS) binary asset transfer.
 *
 * Communicates with the protected `/api/v1` routes on the Kosh Axum server.
 * Injected with [AuthOkHttpClient] to ensure that every request automatically includes
 * the active 118-byte bearer token via [AuthInterceptor].
 *
 * Implements zero-copy streaming invariants to handle large media files without exhausting
 * Android process memory or violating the backend's strict `fallocate(2)` requirements.
 *
 * @param client The authenticated OkHttp client instance.
 */
@Singleton
class AssetClient @Inject constructor(
    @AuthOkHttpClient private val client: OkHttpClient
) {
    private val baseUrl = "http://192.168.240.1:6969/api/v1"

    /**
     * Uploads a binary asset from an Android content [Uri] to the server CAS vault.
     *
     * Streams data in 64 KiB chunks via [StreamingUriRequestBody] to `POST /api/v1/upload/{tag.id}`.
     * Declares exact `Content-Length` via [ContentResolver] to satisfy the backend's `fallocate(2)`
     * contiguous disk block pre-allocation on spinning mechanical storage.
     *
     * @param contentResolver The Android resolver used to open the asset input stream.
     * @param uri The content [Uri] of the file to be uploaded.
     * @param tag The [AssetTag] specifying the destination storage tier (e.g. [AssetTag.GALLERY_ITEM]).
     * @param originalFileName Synthetic or original file name passed via the `X-File-Name` header.
     * @return [NetworkResult.Success] containing the response string on successful upload,
     *   or [NetworkResult.Failure] describing the error.
     */
    fun uploadAsset(
        contentResolver: ContentResolver,
        uri : Uri,
        tag: AssetTag,
        originalFileName: String
    ): NetworkResult<String> {
        val requestBody = StreamingUriRequestBody(contentResolver, uri)

        val request = Request.Builder()
            .url("$baseUrl/upload/${tag.id}")
            .addHeader("X-File-Name", originalFileName)
            .post(requestBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body.string()
                if (response.isSuccessful) {
                    NetworkResult.Success(bodyText)
                } else {
                    NetworkResult.fromHttp(response.code, bodyText)
                }
            }
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Downloads a binary asset from the server CAS vault identified by its BLAKE3 content hash.
     *
     * Performs an HTTP GET request to `GET /api/v1/assets/{hash}` and streams the response body
     * directly to a cache file using zero-copy streaming, avoiding full-file buffering on the heap.
     *
     * @param hash The 64-character lowercase hex BLAKE3 content hash of the requested asset.
     * @param tag The [AssetTag] category of the asset.
     * @param cacheDir The destination directory where the restored file should be written.
     * @return [NetworkResult.Success] containing the downloaded [File],
     *   or [NetworkResult.Failure] if the asset cannot be retrieved.
     */
    fun downloadAsset(hash: String, tag: AssetTag, cacheDir: File): NetworkResult<File> {
        val request = Request.Builder()
            .url("$baseUrl/assets/$hash")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return NetworkResult.fromHttp(response.code, response.body.string())
                }
                val downloadFile = File(cacheDir, "restored_$hash.jpg")
                response.body.byteStream().use { inputStream ->
                    FileOutputStream(downloadFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                NetworkResult.Success(downloadFile)
            }
        } catch (e : Exception) {
            NetworkResult.fromThrowable(e)
        }
    }
}