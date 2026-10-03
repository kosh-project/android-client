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

@Singleton
class AssetClient @Inject constructor(
    @AuthOkHttpClient private val client: OkHttpClient
) {
    private val baseUrl = "http://192.168.240.1:6969/api/v1"

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