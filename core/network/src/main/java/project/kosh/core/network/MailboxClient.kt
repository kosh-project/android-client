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
import project.kosh.core.model.MailboxAllocationResponse
import project.kosh.core.model.MailboxFulfillRequest
import project.kosh.core.model.MailboxPickupResponse
import project.kosh.core.model.MailboxPollState
import project.kosh.core.model.NetworkResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network client for the ephemeral ECDH device-to-device pairing mailbox.
 *
 * Communicates with the Axum server's `/api/v1/devices/mailbox` endpoints:
 * - `POST /api/v1/devices/mailbox`: Allocates a new 8-character Crockford Base32 mailbox in server RAM.
 * - `POST /api/v1/devices/mailbox/{id}/fulfill`: Deposits the E2EE pairing payload (120..8192 hex chars).
 * - `GET /api/v1/devices/mailbox/{id}`: Polls pending status or retrieves and consumes the deposited payload.
 *
 * All operations run on [Dispatchers.IO] and return classified [NetworkResult] instances.
 *
 * @param client Authenticated OkHttp client providing dynamic routing, certificate pinning, and session token injection.
 * @param serverConfigStore Dynamic server endpoint configuration store.
 */
@Singleton
class MailboxClient @Inject constructor(
    @AuthOkHttpClient private val client: OkHttpClient,
    private val serverConfigStore: ServerConfigurationStore
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    private fun getBaseUrl(): String = "${serverConfigStore.getBaseUrl()}/api/v1/devices/mailbox"

    /**
     * Allocates a fresh ephemeral mailbox on the server.
     *
     * @param token Optional explicit bearer token. If null, relies on [AuthOkHttpClient]'s [AuthInterceptor].
     * @return [NetworkResult.Success] containing [MailboxAllocationResponse] with the 8-char Crockford ID and TTL.
     */
    suspend fun allocateMailbox(token: String? = null): NetworkResult<MailboxAllocationResponse> =
        withContext(Dispatchers.IO) {
            val url = getBaseUrl()
            val requestBuilder = Request.Builder()
                .url(url)
                .post("{}".toRequestBody(jsonMediaType))

            if (!token.isNullOrBlank()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            try {
                client.newCall(requestBuilder.build()).execute().use { response ->
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
     * Fulfills an allocated mailbox by depositing the sender's E2EE payload.
     *
     * @param mailboxId The 8-character Crockford Base32 identifier.
     * @param payloadHex 120..8192 hex characters representing the encrypted pairing envelope.
     * @param token Optional explicit bearer token.
     * @return [NetworkResult.Success] on HTTP 204 No Content, or [NetworkResult.Failure].
     */
    suspend fun fulfillMailbox(
        mailboxId: String,
        payloadHex: String,
        token: String? = null
    ): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl()}/$mailboxId/fulfill"
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
            client.newCall(requestBuilder.build()).execute().use { response ->
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
     * Polls status or retrieves the deposited payload from an ephemeral mailbox.
     *
     * Note: If the mailbox is fulfilled, this call retrieves the payload and the server
     * deletes the mailbox from memory immediately ("burn-after-reading").
     *
     * @param mailboxId The 8-character Crockford Base32 identifier.
     * @param token Optional explicit bearer token.
     * @return [NetworkResult.Success] containing [MailboxPickupResponse], or [NetworkResult.Failure].
     */
    suspend fun pickupMailbox(
        mailboxId: String,
        token: String? = null
    ): NetworkResult<MailboxPickupResponse> = withContext(Dispatchers.IO) {
        val url = "${getBaseUrl()}/$mailboxId"
        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
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
     * Continuously polls an allocated mailbox until it is fulfilled, fails, or times out.
     *
     * @param mailboxId The 8-character Crockford Base32 identifier.
     * @param token Optional explicit bearer token.
     * @param timeoutMillis Maximum duration to poll before giving up (default 300 seconds / 5 minutes).
     * @param pollIntervalMillis Interval between successive GET requests (default 1.5 seconds).
     * @return Cold [Flow] emitting [MailboxPollState].
     */
    fun pollPickup(
        mailboxId: String,
        token: String? = null,
        timeoutMillis: Long = 300_000L,
        pollIntervalMillis: Long = 1_500L
    ): Flow<MailboxPollState> = flow {
        val startTime = System.currentTimeMillis()

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= timeoutMillis) {
                emit(MailboxPollState.Failed("Mailbox pairing timed out after ${timeoutMillis / 1000}s"))
                return@flow
            }

            when (val result = pickupMailbox(mailboxId, token)) {
                is NetworkResult.Success -> {
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
                is NetworkResult.Failure -> {
                    emit(MailboxPollState.Failed("Mailbox pickup error: ${result.userMessage}"))
                    return@flow
                }
            }

            delay(pollIntervalMillis)
        }
    }
}
