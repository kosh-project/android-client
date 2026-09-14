package project.kosh.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssetClient @Inject constructor(
    @AuthOkHttpClient private val client: OkHttpClient
) {
    private val baseUrl = "http://192.168.240.1:6969/api/v1"

    fun uploadAsset(file: File, tag: String, originalFileName: String): Boolean {
        val requestBody = file.asRequestBody("application/octet-stream".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/upload/$tag")
            .addHeader("X-File-Name", originalFileName)
            .post(requestBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e : Exception) {
            false
        }
    }
}