package project.kosh.core.network


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import project.kosh.core.model.AuthRequest
import project.kosh.core.model.AuthResponse
import project.kosh.core.model.ChallengeResponse
import project.kosh.core.model.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthClient @Inject constructor(
    @PublicOkHttpClient private val client: OkHttpClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    // TODO: Ugly url, we'll replace it later
    private val baseUrl = "http://192.168.240.1:23342/api"

    suspend fun fetchChallenge(action: String): NetworkResult<ChallengeResponse> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/auth/challenge?action=$action")
                .get()
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()

                    if (response.isSuccessful) {
                        NetworkResult.Success(json.decodeFromString<ChallengeResponse>(body))
                    } else {
                        NetworkResult.fromHttp(response.code, body)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.fromThrowable(e)
            }
        }

    suspend fun register(requestDto: AuthRequest, hashcashHeader: String): NetworkResult<Unit> =
        withContext(
            Dispatchers.IO
        ) {
            val jsonBody = Json.encodeToString(AuthRequest.serializer(), requestDto)
            val requestBody = jsonBody.toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$baseUrl/auth/register")
                .addHeader("X-Hashcash", hashcashHeader)
                .post(requestBody)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        NetworkResult.Success(Unit)
                    } else {
                        val body = response.body.string()
                        NetworkResult.fromHttp(response.code, body)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.fromThrowable(e)
            }
        }

    suspend fun login(requestDto: AuthRequest, hashcashHeader: String): NetworkResult<String> = withContext(
        Dispatchers.IO
    ) {
        val jsonBody = Json.encodeToString(AuthRequest.serializer(), requestDto)
        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            // TODO: fixate to better url with avahi config later
            .url("$baseUrl/auth/login")
            .addHeader("X-Hashcash", hashcashHeader)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    val authResponse = json.decodeFromString<AuthResponse>(body)
                    NetworkResult.Success(authResponse.token)
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e : Exception) {
            NetworkResult.fromThrowable(e)
        }
    }
}