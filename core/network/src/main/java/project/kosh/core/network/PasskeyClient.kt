package project.kosh.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import project.kosh.core.model.AuthResponse
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PasskeyChallengeResponse
import project.kosh.core.model.PasskeyLoginFinishRequest
import project.kosh.core.model.PasskeyMetadataRow
import project.kosh.core.model.PasskeyRegisterFinishRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-level HTTP network client for FIDO2 / WebAuthn passkey authentication and credential management.
 *
 * Implements communication with backend passkey endpoints:
 * - Unauthenticated Discoverable Sign-in: `POST /api/auth/passkey/login/...` via [PublicOkHttpClient].
 * - Authenticated Credential Lifecycle: `POST|GET|DELETE /api/v1/auth/passkey/...` via [AuthOkHttpClient].
 *
 * All operations execute within [Dispatchers.IO] and return classified [NetworkResult] instances.
 *
 * @param publicClient The unauthenticated OkHttp client instance without bearer token injection.
 * @param authClient The authenticated OkHttp client instance injecting session bearer tokens.
 * @param serverConfigStore Dynamic server endpoint configuration store.
 */
@Singleton
class PasskeyClient @Inject constructor(
    @PublicOkHttpClient private val publicClient: OkHttpClient,
    @AuthOkHttpClient private val authClient: OkHttpClient,
    private val serverConfigStore: ServerConfigurationStore
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    private fun getApiBaseUrl(): String = "${serverConfigStore.getBaseUrl()}/api"
    private fun getV1BaseUrl(): String = "${serverConfigStore.getBaseUrl()}/api/v1"

    /**
     * Initiates discoverable passkey authentication ceremony.
     *
     * Issues an unauthenticated HTTP POST request to `/api/auth/passkey/login/start`.
     *
     * @return [NetworkResult.Success] containing [PasskeyChallengeResponse] on 200 OK, or [NetworkResult.Failure].
     */
    suspend fun startLogin(): NetworkResult<PasskeyChallengeResponse> = withContext(Dispatchers.IO) {
        try {
            val url = "${getApiBaseUrl()}/auth/passkey/login/start"
            val request = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(jsonMediaType))
                .build()

            publicClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    NetworkResult.Success(json.decodeFromString<PasskeyChallengeResponse>(body))
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Completes discoverable passkey authentication ceremony.
     *
     * Issues an unauthenticated HTTP POST request to `/api/auth/passkey/login/finish`.
     *
     * @param request Payload containing the challenge ID and WebAuthn authenticator assertion response.
     * @return [NetworkResult.Success] containing [AuthResponse] with bearer token and wrapped vault key, or [NetworkResult.Failure].
     */
    suspend fun finishLogin(request: PasskeyLoginFinishRequest): NetworkResult<AuthResponse> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${getApiBaseUrl()}/auth/passkey/login/finish"
                val jsonBody = json.encodeToString(PasskeyLoginFinishRequest.serializer(), request)
                val requestBody = jsonBody.toRequestBody(jsonMediaType)

                val httpRequest = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                publicClient.newCall(httpRequest).execute().use { response ->
                    val body = response.body.string()
                    if (response.isSuccessful) {
                        NetworkResult.Success(json.decodeFromString<AuthResponse>(body))
                    } else {
                        NetworkResult.fromHttp(response.code, body)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.fromThrowable(e)
            }
        }

    /**
     * Initiates authenticated passkey registration ceremony for the active user.
     *
     * Issues an authenticated HTTP POST request to `/api/v1/auth/passkey/register/start`.
     *
     * @param token Optional explicit bearer token. If null, relies on [AuthOkHttpClient]'s [AuthInterceptor].
     * @return [NetworkResult.Success] containing [PasskeyChallengeResponse] on 200 OK, or [NetworkResult.Failure].
     */
    suspend fun startRegistration(token: String? = null): NetworkResult<PasskeyChallengeResponse> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${getV1BaseUrl()}/auth/passkey/register/start"
                val requestBuilder = Request.Builder()
                    .url(url)
                    .post("{}".toRequestBody(jsonMediaType))

                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                authClient.newCall(requestBuilder.build()).execute().use { response ->
                    val body = response.body.string()
                    if (response.isSuccessful) {
                        NetworkResult.Success(json.decodeFromString<PasskeyChallengeResponse>(body))
                    } else {
                        NetworkResult.fromHttp(response.code, body)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.fromThrowable(e)
            }
        }

    /**
     * Completes authenticated passkey registration ceremony.
     *
     * Issues an authenticated HTTP POST request to `/api/v1/auth/passkey/register/finish`.
     *
     * @param request Payload containing the challenge ID, authenticator attestation, and client-encrypted device label.
     * @param token Optional explicit bearer token.
     * @return [NetworkResult.Success] on HTTP 201 Created, or [NetworkResult.Failure].
     */
    suspend fun finishRegistration(
        request: PasskeyRegisterFinishRequest,
        token: String? = null
    ): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "${getV1BaseUrl()}/auth/passkey/register/finish"
            val jsonBody = json.encodeToString(PasskeyRegisterFinishRequest.serializer(), request)
            val requestBody = jsonBody.toRequestBody(jsonMediaType)

            val requestBuilder = Request.Builder()
                .url(url)
                .post(requestBody)

            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            authClient.newCall(requestBuilder.build()).execute().use { response ->
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
     * Lists all passkey credentials registered to the authenticated caller.
     *
     * Issues an authenticated HTTP GET request to `/api/v1/auth/passkey`.
     *
     * @param token Optional explicit bearer token.
     * @return [NetworkResult.Success] containing [List] of [PasskeyMetadataRow] ordered newest to oldest, or [NetworkResult.Failure].
     */
    suspend fun listPasskeys(token: String? = null): NetworkResult<List<PasskeyMetadataRow>> =
        withContext(Dispatchers.IO) {
            try {
                val url = "${getV1BaseUrl()}/auth/passkey"
                val requestBuilder = Request.Builder()
                    .url(url)
                    .get()

                if (!token.isNullOrBlank()) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                authClient.newCall(requestBuilder.build()).execute().use { response ->
                    val body = response.body.string()
                    if (response.isSuccessful) {
                        NetworkResult.Success(json.decodeFromString<List<PasskeyMetadataRow>>(body))
                    } else {
                        NetworkResult.fromHttp(response.code, body)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.fromThrowable(e)
            }
        }

    /**
     * Permanently revokes and deletes a passkey credential owned by the caller.
     *
     * Issues an authenticated HTTP DELETE request to `/api/v1/auth/passkey/{credential_id}`.
     *
     * @param credentialId Hex-encoded credential identifier.
     * @param token Optional explicit bearer token.
     * @return [NetworkResult.Success] on HTTP 204 No Content, or [NetworkResult.Failure].
     */
    suspend fun deletePasskey(
        credentialId: String,
        token: String? = null
    ): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = "${getV1BaseUrl()}/auth/passkey/$credentialId"
            val requestBuilder = Request.Builder()
                .url(url)
                .delete()

            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            authClient.newCall(requestBuilder.build()).execute().use { response ->
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
