package project.kosh.core.network


import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import project.kosh.core.model.AuthCredential
import project.kosh.core.model.AuthResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthClient @Inject constructor(
    @PublicOkHttpClient private val client: OkHttpClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val baseUrl = "http://192.168.240.1:6969/api"

    fun register(credential: AuthCredential): Boolean {
        val jsonBody = Json.encodeToString(credential)

        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/auth/register")
            // TODO: we'll fixate to a better url with avahi config later
            .method("POST", requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            return response.isSuccessful
        }
    }

    fun login(credential: AuthCredential): String? {
        val jsonBody = Json.encodeToString(credential)
        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            // TODO: fixate to better url with avahi config later
            .url("$baseUrl/auth/login")
            .method("POST", requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null

            val responseBodyString = response.body.string()

            val response = Json.decodeFromString<AuthResponse>(responseBodyString)

            return response.token
        }
    }
}