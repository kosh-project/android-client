package project.kosh.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import project.kosh.core.model.LinkTicketResponse
import project.kosh.core.model.MailboxAllocationResponse
import project.kosh.core.model.MailboxFulfillRequest
import project.kosh.core.model.MailboxJoinRequest
import project.kosh.core.model.MailboxJoinResponse
import project.kosh.core.model.MailboxOpenRequest
import project.kosh.core.model.MailboxOpenResponse
import project.kosh.core.model.MailboxPickupResponse
import project.kosh.core.model.MailboxPollState
import project.kosh.core.model.MailboxStatusResponse
import project.kosh.core.model.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network client for the ephemeral ECDH device-to-device pairing mailbox subsystem.
 *
 * Communicates with the Axum backend across two route families:
 * 1. Unauthenticated routes via [publicClient] (`/api/auth/mailbox/...`):
 *    - `POST /api/auth/mailbox/open`: Opens an unauthenticated mailbox with PoW (Flow A).
 *    - `POST /api/auth/mailbox/{id}/join`: Joins an offered mailbox with PoW (Flow B).
 *    - `GET /api/auth/mailbox/{id}/pickup`: Retrieves fulfilled payload using polling token (Flow A & B).
 * 2. Authenticated device routes via [authClient] (`/api/v1/devices/...`):
 *    - `POST /api/v1/devices/mailbox`: Allocates an offered mailbox in Waiting state (Flow B).
 *    - `GET /api/v1/devices/mailbox/{id}/status`: Polls status of offered mailbox (Flow B & A).
 *    - `POST /api/v1/devices/mailbox/{id}/fulfill`: Deposits E2EE payload into ready mailbox.
 *    - `POST /api/v1/devices/link-ticket`: Mints a short-lived single-use registration ticket.
 *
 * All operations run on [Dispatchers.IO] and return classified [NetworkResult] instances.
 *
 * @param publicClient Unauthenticated OkHttp client without session token injection.
 * @param authClient Authenticated OkHttp client injecting bearer tokens.
 * @param serverConfigStore Dynamic server endpoint configuration store.
 */
@Singleton
class MailboxClient @Inject constructor(
    @PublicOkHttpClient private val publicClient: OkHttpClient,
    @AuthOkHttpClient private val authClient: OkHttpClient,
    private val serverConfigStore: ServerConfigurationStore
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    private fun getAuthBaseUrl(): String = "${serverConfigStore.getBaseUrl()}/api/auth/mailbox"
    private fun getDevicesBaseUrl(): String = "${serverConfigStore.getBaseUrl()}/api/v1/devices"

    // ==========================================
    // Flow A & Flow B: Unauthenticated Endpoints
    // ==========================================

    /**
     * Opens an unauthenticated ephemeral mailbox (Flow A: Receiver displays QR code).
     *
     * Route: `POST /api/auth/mailbox/open` (guarded by `pow_guard` and `with_auth_ip_limit`).
     *
     * @param pubTempHex 64-hex-character string representing receiver's 32-byte X25519 public key.
     * @param hashcashHeader Completed 168-byte or 165-byte `X-Hashcash` Proof-of-Work header string.
     * @return [NetworkResult.Success] containing [MailboxOpenResponse], or [NetworkResult.Failure].
     */
    suspend fun openMailbox(
        pubTempHex: String,
        hashcashHeader: String
    ): NetworkResult<MailboxOpenResponse> = withContext(Dispatchers.IO) {
        val url = "${getAuthBaseUrl()}/open"
        val requestDto = MailboxOpenRequest(pubTemp = pubTempHex)
        val jsonBody = json.encodeToString(MailboxOpenRequest.serializer(), requestDto)
        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .header("X-Hashcash", hashcashHeader)
            .post(requestBody)
            .build()

        try {
            publicClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    NetworkResult.Success(json.decodeFromString<MailboxOpenResponse>(body))
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Joins an existing offered mailbox (Flow B: Receiver scans sender QR code).
     *
     * Route: `POST /api/auth/mailbox/{id}/join` (guarded by `pow_guard` and `with_auth_ip_limit`).
     *
     * @param mailboxId 8-character Crockford Base32 identifier.
     * @param pubTempHex 64-hex-character string representing receiver's 32-byte X25519 public key.
     * @param hashcashHeader Completed `X-Hashcash` header string.
     * @return [NetworkResult.Success] containing [MailboxJoinResponse] with polling token, or [NetworkResult.Failure].
     */
    suspend fun joinMailbox(
        mailboxId: String,
        pubTempHex: String,
        hashcashHeader: String
    ): NetworkResult<MailboxJoinResponse> = withContext(Dispatchers.IO) {
        val url = "${getAuthBaseUrl()}/$mailboxId/join"
        val requestDto = MailboxJoinRequest(pubTemp = pubTempHex)
        val jsonBody = json.encodeToString(MailboxJoinRequest.serializer(), requestDto)
        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url(url)
            .header("X-Hashcash", hashcashHeader)
            .post(requestBody)
            .build()

        try {
            publicClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    NetworkResult.Success(json.decodeFromString<MailboxJoinResponse>(body))
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Polls status or retrieves the deposited payload from an ephemeral mailbox.
     *
     * Route: `GET /api/auth/mailbox/{id}/pickup` (rate-limited by `with_auth_ip_limit`).
     * Single-use burn-after-reading: when fulfilled, the server evicts the mailbox from RAM immediately.
     *
     * @param mailboxId 8-character Crockford Base32 identifier.
     * @param pollingToken 64-hex-character polling authorization token.
     * @return [NetworkResult.Success] containing [MailboxPickupResponse], or [NetworkResult.Failure].
     */
    suspend fun pickupMailbox(
        mailboxId: String,
        pollingToken: String
    ): NetworkResult<MailboxPickupResponse> = withContext(Dispatchers.IO) {
        val url = "${getAuthBaseUrl()}/$mailboxId/pickup"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pollingToken")
            .get()
            .build()

        try {
            publicClient.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    NetworkResult.Success(json.decodeFromString<MailboxPickupResponse>(body))
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Continuously polls an allocated mailbox until fulfilled, failed, or timed out.
     *
     * Rate-Limit Invariant: Because unauthenticated auth routes are subject to the 2 req/s IP governor,
     * the polling interval defaults to 1,500ms to avoid HTTP 429 Too Many Requests.
     *
     * @param mailboxId 8-character Crockford Base32 identifier.
     * @param pollingToken 64-hex-character polling authorization token.
     * @param timeoutMillis Maximum duration to poll before timing out (default 300 seconds).
     * @param pollIntervalMillis Interval between successive polls (default 1,500ms).
     * @return Cold [Flow] emitting progressive [MailboxPollState] transitions.
     */
    fun pollPickup(
        mailboxId: String,
        pollingToken: String,
        timeoutMillis: Long = 300_000L,
        pollIntervalMillis: Long = 1_500L
    ): Flow<MailboxPollState> = flow {
        val startTime = System.currentTimeMillis()
        var consecutiveTransientErrors = 0
        val maxConsecutiveErrors = 5

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= timeoutMillis) {
                emit(MailboxPollState.Failed("Mailbox pairing timed out after ${timeoutMillis / 1000}s"))
                return@flow
            }

            var nextDelayMillis = pollIntervalMillis

            when (val result = pickupMailbox(mailboxId, pollingToken)) {
                is NetworkResult.Success -> {
                    consecutiveTransientErrors = 0
                    val response = result.data
                    if (response.isFulfilled) {
                        emit(MailboxPollState.Fulfilled(response.payload!!))
                        return@flow
                    } else if (response.isPending) {
                        emit(MailboxPollState.Pending)
                    } else {
                        emit(MailboxPollState.Failed("Unexpected mailbox status: ${response.status}"))
                        return@flow
                    }
                }
                is NetworkResult.Http.NotFound -> {
                    emit(MailboxPollState.Failed("Mailbox not found, expired, or already consumed (404)"))
                    return@flow
                }
                is NetworkResult.Http.Unauthorized -> {
                    emit(MailboxPollState.Failed("Invalid or expired polling token (401)"))
                    return@flow
                }
                is NetworkResult.Http.RateLimited -> {
                    val retrySecs = result.retryAfterSeconds?.toLong() ?: 3L
                    nextDelayMillis = maxOf(pollIntervalMillis, retrySecs * 1000L)
                }
                is NetworkResult.Failure -> {
                    consecutiveTransientErrors++
                    if (consecutiveTransientErrors >= maxConsecutiveErrors) {
                        emit(MailboxPollState.Failed("Mailbox pickup error: ${result.userMessage}"))
                        return@flow
                    }
                }
            }

            delay(nextDelayMillis)
        }
    }

    // ==========================================
    // Flow B & Flow A: Authenticated Endpoints
    // ==========================================

    /**
     * Allocates a fresh ephemeral mailbox in Waiting state (Flow B: Sender displays QR code).
     *
     * Route: `POST /api/v1/devices/mailbox` (protected by `mac_guard` and `auth_guard`).
     *
     * @param token Optional explicit session bearer token.
     * @return [NetworkResult.Success] containing [MailboxAllocationResponse], or [NetworkResult.Failure].
     */
    suspend fun allocateMailbox(token: String? = null): NetworkResult<MailboxAllocationResponse> =
        withContext(Dispatchers.IO) {
            val url = "${getDevicesBaseUrl()}/mailbox"
            val requestBuilder = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(jsonMediaType))

            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            try {
                authClient.newCall(requestBuilder.build()).execute().use { response ->
                    val body = response.body.string()
                    if (response.isSuccessful) {
                        NetworkResult.Success(json.decodeFromString<MailboxAllocationResponse>(body))
                    } else {
                        NetworkResult.fromHttp(response.code, body)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.fromThrowable(e)
            }
        }

    /**
     * Polls the status of an offered mailbox (Flow B: Sender awaits receiver join).
     *
     * Route: `GET /api/v1/devices/mailbox/{id}/status` (protected by `mac_guard` and `auth_guard`).
     *
     * @param mailboxId 8-character Crockford Base32 identifier.
     * @param token Optional explicit session bearer token.
     * @return [NetworkResult.Success] containing [MailboxStatusResponse], or [NetworkResult.Failure].
     */
    suspend fun checkStatus(
        mailboxId: String,
        token: String? = null
    ): NetworkResult<MailboxStatusResponse> = withContext(Dispatchers.IO) {
        val url = "${getDevicesBaseUrl()}/mailbox/$mailboxId/status"
        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        try {
            authClient.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    NetworkResult.Success(json.decodeFromString<MailboxStatusResponse>(body))
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Fulfills an allocated mailbox by depositing the sender's E2EE payload.
     *
     * Route: `POST /api/v1/devices/mailbox/{id}/fulfill` (protected by `mac_guard` and `auth_guard`).
     *
     * @param mailboxId 8-character Crockford Base32 identifier.
     * @param payloadHex 120..8192 hex characters representing the encrypted pairing envelope.
     * @param token Optional explicit session bearer token.
     * @return [NetworkResult.Success] on HTTP 204 No Content, or [NetworkResult.Failure].
     */
    suspend fun fulfillMailbox(
        mailboxId: String,
        payloadHex: String,
        token: String? = null
    ): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        val url = "${getDevicesBaseUrl()}/mailbox/$mailboxId/fulfill"
        val requestDto = MailboxFulfillRequest(payload = payloadHex)
        val jsonBody = json.encodeToString(MailboxFulfillRequest.serializer(), requestDto)
        val requestBody = jsonBody.toRequestBody(jsonMediaType)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        try {
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
     * Mints a short-lived single-use registration ticket for passkey enrollment.
     *
     * Route: `POST /api/v1/devices/link-ticket` (protected by `mac_guard` and `auth_guard`).
     *
     * @param token Optional explicit session bearer token.
     * @return [NetworkResult.Success] containing [LinkTicketResponse] with the 64-hex ticket and 300s TTL.
     */
    suspend fun mintLinkTicket(token: String? = null): NetworkResult<LinkTicketResponse> =
        withContext(Dispatchers.IO) {
            val url = "${getDevicesBaseUrl()}/link-ticket"
            val requestBuilder = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(jsonMediaType))

            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            try {
                authClient.newCall(requestBuilder.build()).execute().use { response ->
                    val body = response.body.string()
                    if (response.isSuccessful) {
                        NetworkResult.Success(json.decodeFromString<LinkTicketResponse>(body))
                    } else {
                        NetworkResult.fromHttp(response.code, body)
                    }
                }
            } catch (e: Exception) {
                NetworkResult.fromThrowable(e)
            }
        }
}
