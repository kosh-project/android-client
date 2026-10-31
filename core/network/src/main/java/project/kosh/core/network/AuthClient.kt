package project.kosh.core.network


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import project.kosh.core.model.AuthResponse
import project.kosh.core.model.ChallengeResponse
import project.kosh.core.model.LoginRequest
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.RegisterRequest
import project.kosh.core.model.VaultRecoveryRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-level HTTP network client managing authentication and challenge endpoints.
 *
 * This client communicates with the public `/api/auth` routes on the Kosh Axum server.
 * It is configured with [PublicOkHttpClient] to ensure that requests are made without
 * an `Authorization` header, preventing interference with the server's ingress rate governors.
 *
 * All operations execute within [Dispatchers.IO] and return classified [NetworkResult] instances.
 *
 * @param client The unauthenticated OkHttp client instance.
 */
@Singleton
class AuthClient @Inject constructor(
    @PublicOkHttpClient private val client: OkHttpClient,
    private val serverConfigStore: ServerConfigurationStore
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    private fun getBaseUrl(): String = "${serverConfigStore.getBaseUrl()}/api"

    /**
     * Requests a dynamic, stateless Proof-of-Work challenge from the backend server.
     *
     * Issues an HTTP GET request to `/api/auth/challenge?action={action}`.
     * The returned challenge has a strict 15-second time-to-live (TTL) on the backend.
     *
     * @param action The intended authentication action (`"login"` or `"register"`).
     * @return [NetworkResult.Success] containing [ChallengeResponse] if retrieved,
     *   or [NetworkResult.Failure] describing the error.
     */
    suspend fun fetchChallenge(action: String): NetworkResult<ChallengeResponse> =
        withContext(Dispatchers.IO) {
            val baseUrl = getBaseUrl()
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

    /**
     * Registers a new user account with the backend server.
     *
     * Transmits an HTTP POST request to `/api/auth/register` with:
     * - `X-Hashcash`: The 168-byte Proof-of-Work solution header containing the identity hash and nonce.
     * - Body: JSON-encoded [RegisterRequest] containing `auth_verifier` and 60-byte `wrapped_vault_key`.
     *
     * @param requestDto The DTO carrying the Argon2id verifier and wrapped vault key.
     * @param hashcashHeader The solved 168-byte Hashcash header string.
     * @return [NetworkResult.Success] containing [Unit] on successful registration,
     *   or [NetworkResult.Failure] (such as [NetworkResult.Http.Conflict] if already registered).
     */
    suspend fun register(requestDto: RegisterRequest, hashcashHeader: String): NetworkResult<Unit> =
        withContext(
            Dispatchers.IO
        ) {
            val baseUrl = getBaseUrl()
            val jsonBody = json.encodeToString(RegisterRequest.serializer(), requestDto)
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

    /**
     * Authenticates an existing user account and retrieves a session token and wrapped vault key.
     *
     * Transmits an HTTP POST request to `/api/auth/login` with:
     * - `X-Hashcash`: The 165-byte Proof-of-Work solution header.
     * - Body: JSON-encoded [LoginRequest] containing the Argon2id `auth_verifier`.
     *
     * On success, the server responds with an [AuthResponse] carrying:
     * - 118-byte composite bearer token (`<session_id>.<expires_at_hex>.<blake3_mac>`)
     * - 120-character hex string representing the 60-byte ChaCha20/AES-GCM `wrapped_vault_key`
     *
     * @param requestDto The DTO carrying the Argon2id verifier.
     * @param hashcashHeader The solved 165-byte Hashcash header string.
     * @return [NetworkResult.Success] containing [AuthResponse],
     *   or [NetworkResult.Failure] if credentials or challenge are invalid.
     */
    suspend fun login(requestDto: LoginRequest, hashcashHeader: String): NetworkResult<AuthResponse> = withContext(
        Dispatchers.IO
    ) {
        val baseUrl = getBaseUrl()
        val jsonBody = json.encodeToString(LoginRequest.serializer(), requestDto)
        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/auth/login")
            .addHeader("X-Hashcash", hashcashHeader)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    val authResponse = json.decodeFromString<AuthResponse>(body)
                    NetworkResult.Success(authResponse)
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e : Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Rotates credentials and the re-wrapped vault key on the server.
     *
     * Calls `PUT /api/v1/auth/recovery` with the active bearer token.
     *
     * @param requestDto Payload containing new identity hash, verifier, and re-wrapped vault key.
     * @param bearerToken Active 118-byte bearer token.
     * @return [NetworkResult.Success] on 204 No Content, or [NetworkResult.Failure].
     */
    suspend fun recoverCredentials(
        requestDto: VaultRecoveryRequest,
        bearerToken: String
    ): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        val jsonBody = json.encodeToString(VaultRecoveryRequest.serializer(), requestDto)
        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$baseUrl/v1/auth/recovery")
            .addHeader("Authorization", "Bearer $bearerToken")
            .put(requestBody)
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
}