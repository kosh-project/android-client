package project.kosh.core.network

import android.content.ContentResolver
import android.net.Network
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import project.kosh.core.model.AssetCursor
import project.kosh.core.model.AssetPage
import project.kosh.core.model.AssetTag
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.UploadResponse
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
    private val json = Json { ignoreUnknownKeys = true }
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
    suspend fun uploadAsset(
        contentResolver: ContentResolver,
        uri : Uri,
        tag: AssetTag,
        originalFileName: String
    ): NetworkResult<String> = withContext(Dispatchers.IO) {
        val requestBody = StreamingUriRequestBody(contentResolver, uri)

        val request = Request.Builder()
            .url("$baseUrl/upload/${tag.id}")
            .addHeader("X-File-Name", originalFileName)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body.string()
                if (!response.isSuccessful) {
                    return@use NetworkResult.fromHttp(response.code, bodyText)
                }

                val uploadResponse = json.decodeFromString<UploadResponse>(bodyText)
                val hash = uploadResponse.hash

                if (!uploadResponse.isSuccess || hash == null) {
                    return@use NetworkResult.fromHttp(500, bodyText)
                }

                NetworkResult.Success(hash)
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
    suspend fun downloadAsset(
        hash: String,
        destinationFile: File
    ): NetworkResult<File> = withContext(Dispatchers.IO){
        val request = Request.Builder()
            .url("$baseUrl/assets/$hash")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use NetworkResult.fromHttp(response.code, response.body.string())
                }

                response.body.byteStream().use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream, 64 * 1024)
                    }
                }

                NetworkResult.Success(destinationFile)
            }
        } catch (e : Exception) {
            NetworkResult.fromThrowable(e)
        }
    }
    suspend fun listAssets(
        tag: AssetTag,
        limit: Int = 50,
        cursor: AssetCursor? = null
    ): NetworkResult<AssetPage> = withContext(Dispatchers.IO) {
        val urlBuilder = "$baseUrl/assets".toHttpUrl()
            .newBuilder()
            .addQueryParameter("tag", tag.id.toString())
            .addQueryParameter("limit", limit.toString())

        if (cursor != null) {
            urlBuilder
                .addQueryParameter("before_time", cursor.beforeTime.toString())
                .addQueryParameter("before_id", cursor.beforeId)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body.string()
                if (!response.isSuccessful) {
                    return@use NetworkResult.fromHttp(response.code, bodyText)
                }

                val page = json.decodeFromString<AssetPage>(bodyText)
                NetworkResult.Success(page)
            }
        } catch (e : Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    suspend fun deleteAsset(
        hash: String
    ) : NetworkResult<Unit> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/assets/$hash")
            .delete()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use NetworkResult.fromHttp(response.code, response.body.string())
                }

                NetworkResult.Success(Unit)
            }
        } catch (e : Exception) {
            NetworkResult.fromThrowable(e)
        }
    }
}




