package project.kosh.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import project.kosh.core.model.DeltaAppendResponse
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PruneResponse
import project.kosh.core.model.SnapshotRegisterRequest
import project.kosh.core.model.SnapshotResponse
import project.kosh.core.model.SyncFrame
import project.kosh.core.model.SyncNamespace
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exception thrown when the sync server returns an unsuccessful HTTP status code.
 *
 * Inherits from [IOException] for backwards compatibility with network error handlers.
 *
 * @property code The HTTP response status code.
 * @property serverMessage Raw response body or diagnostic message from the server.
 */
class SyncHttpException(
    val code: Int,
    val serverMessage: String
) : IOException("HTTP $code: $serverMessage")

/**
 * Network client for the Kosh CRDT delta sync ledger and snapshot lifecycle.
 *
 * Implements communication with backend sync endpoints:
 * - `POST /api/v1/sync/delta?namespace={ns}`: Appends raw encrypted CRDT delta bytes.
 * - `GET /api/v1/sync/delta?namespace={ns}&file={file}&offset={offset}`: Streams Big-Endian binary frames.
 * - `GET /api/v1/sync/snapshot?namespace={ns}`: Retrieves latest registered snapshot metadata.
 * - `POST /api/v1/sync/snapshot`: Registers a newly committed snapshot for faster bootstrap.
 * - `DELETE /api/v1/sync/prune?namespace={ns}`: Prunes sealed segments below the safe device watermark.
 *
 * ### Streaming Invariant:
 * The backend committer pre-allocates a 500-byte header on segment disk files (`b"KOSH"` magic + metadata).
 * When `GET /api/v1/sync/delta` is invoked with `offset = 0`, the server automatically seeks past
 * the 500-byte header via `cmp::max(offset, 500)`. Consequently, the HTTP response stream begins
 * immediately with the first framed binary record `[4B BE length][8B BE sequence][payload]`.
 * The client does NOT skip 500 bytes on the HTTP wire response.
 *
 * @param client Authenticated OkHttp client instance injecting session bearer tokens.
 * @param serverConfigStore Dynamic server endpoint configuration store.
 */
@Singleton
class SyncClient @Inject constructor(
    @AuthOkHttpClient private val client: OkHttpClient,
    private val serverConfigStore: ServerConfigurationStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
        const val DEFAULT_SEGMENT_FILE = "current.log"
        const val MAX_FRAME_PAYLOAD_SIZE = 16 * 1024 * 1024 // 16 MiB bounds protection
    }

    private fun getSyncBaseUrl(): String = "${serverConfigStore.getBaseUrl().trimEnd('/')}/api/v1/sync"

    /**
     * Appends an encrypted CRDT delta payload to the active namespace segment.
     *
     * @param namespace The sync namespace (files or gallery).
     * @param payload Raw binary bytes of the encrypted delta mutation.
     * @return [NetworkResult.Success] containing [DeltaAppendResponse] with sequence ID and file offset.
     */
    suspend fun appendDelta(
        namespace: SyncNamespace,
        payload: ByteArray
    ): NetworkResult<DeltaAppendResponse> = withContext(Dispatchers.IO) {
        val url = "${getSyncBaseUrl()}/delta".toHttpUrl().newBuilder()
            .addQueryParameter("namespace", namespace.value)
            .build()

        val requestBody = payload.toRequestBody(OCTET_STREAM_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    NetworkResult.Success(json.decodeFromString<DeltaAppendResponse>(body))
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Streams framed binary delta records from a namespace segment file.
     *
     * Each frame consists of:
     * - 4 bytes Big-Endian [Int]: payload length
     * - 8 bytes Big-Endian [Long]: sequence ID
     * - length bytes: raw encrypted payload
     *
     * @param namespace The sync namespace (files or gallery).
     * @param fileName Segment file name to read (default `current.log`).
     * @param offset Byte offset in the segment file to seek before streaming (default 0).
     * @return Cold [Flow] emitting parsed [SyncFrame] records as they arrive from the wire.
     * @throws IOException If the HTTP call fails or the stream is prematurely truncated.
     * @throws IllegalStateException If a frame declares an invalid length exceeding [MAX_FRAME_PAYLOAD_SIZE].
     */
    fun streamDeltas(
        namespace: SyncNamespace,
        fileName: String = DEFAULT_SEGMENT_FILE,
        offset: Long = 0L
    ): Flow<SyncFrame> = flow {
        require(fileName.isNotBlank()) { "Segment file name cannot be blank" }
        require(!fileName.contains("..") && !fileName.contains('/') && !fileName.contains('\\')) {
            "Segment file name cannot contain path traversal characters"
        }
        require(fileName.length > 4 && fileName.endsWith(".log", ignoreCase = true)) {
            "Segment file name must end with .log extension and have a valid base name: $fileName"
        }
        require(fileName.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) {
            "Segment file name contains invalid characters: $fileName"
        }
        require(offset >= 0L) {
            "Stream offset cannot be negative: $offset"
        }

        val url = "${getSyncBaseUrl()}/delta".toHttpUrl().newBuilder()
            .addQueryParameter("namespace", namespace.value)
            .addQueryParameter("file", fileName)
            .addQueryParameter("offset", offset.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val call = client.newCall(request)
        val job = currentCoroutineContext()[Job]
        val completionHandle = job?.invokeOnCompletion {
            call.cancel()
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    throw SyncHttpException(response.code, errorBody)
                }

                val source = response.body.source()
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val len = source.readInt()
                    if (len < 0 || len > MAX_FRAME_PAYLOAD_SIZE) {
                        throw IllegalStateException("Invalid frame length: $len (exceeds bounds [0, $MAX_FRAME_PAYLOAD_SIZE])")
                    }
                    val seq = source.readLong()
                    val payload = source.readByteArray(len.toLong())
                    emit(SyncFrame(length = len, sequenceId = seq, payload = payload))
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw e
        } finally {
            completionHandle?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Fetches the latest snapshot metadata for a namespace, if one has been registered.
     *
     * @param namespace The sync namespace (files or gallery).
     * @return [NetworkResult.Success] containing [SnapshotResponse] if present, or null if no snapshot exists.
     */
    suspend fun getLatestSnapshot(
        namespace: SyncNamespace
    ): NetworkResult<SnapshotResponse?> = withContext(Dispatchers.IO) {
        val url = "${getSyncBaseUrl()}/snapshot".toHttpUrl().newBuilder()
            .addQueryParameter("namespace", namespace.value)
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    val trimmed = body.trim()
                    if (trimmed.isEmpty() || trimmed == "null") {
                        NetworkResult.Success(null)
                    } else {
                        NetworkResult.Success(json.decodeFromString<SnapshotResponse>(trimmed))
                    }
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Registers a new snapshot metadata record with the server committer.
     *
     * Accepts either a bare numeric Long scalar response or a JSON object containing an `id` or `snapshot_id`.
     *
     * @param namespace The sync namespace (files or gallery).
     * @param snapshotSeq Monotonic sequence ID up to which state is materialized in this snapshot.
     * @param blobHash 64-character hex BLAKE3 hash of the uploaded CAS blob.
     * @return [NetworkResult.Success] containing the registered snapshot database row ID.
     */
    suspend fun registerSnapshot(
        namespace: SyncNamespace,
        snapshotSeq: Long,
        blobHash: String
    ): NetworkResult<Long> = withContext(Dispatchers.IO) {
        val url = "${getSyncBaseUrl()}/snapshot".toHttpUrl()
        val requestPayload = SnapshotRegisterRequest(
            namespace = namespace,
            snapshotSeq = snapshotSeq,
            blobHash = blobHash
        )
        val jsonBody = json.encodeToString(SnapshotRegisterRequest.serializer(), requestPayload)
        val requestBody = jsonBody.toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    val trimmed = body.trim()
                    val parsedId = trimmed.toLongOrNull() ?: try {
                        val element = json.parseToJsonElement(trimmed)
                        if (element is JsonObject) {
                            element["id"]?.jsonPrimitive?.longOrNull
                                ?: element["snapshot_id"]?.jsonPrimitive?.longOrNull
                                ?: throw IllegalArgumentException("Could not extract snapshot ID from: $trimmed")
                        } else {
                            throw IllegalArgumentException("Unexpected snapshot response: $trimmed")
                        }
                    } catch (e: Exception) {
                        throw e
                    }
                    NetworkResult.Success(parsedId)
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }

    /**
     * Triggers ledger segment pruning on the server for the specified namespace.
     *
     * Sealed segments whose maximum sequence is strictly lower than the lowest active
     * device watermark are unlinked from the server filesystem.
     *
     * @param namespace The sync namespace (files or gallery).
     * @return [NetworkResult.Success] containing [PruneResponse] with safe sequence and pruned segment count.
     */
    suspend fun pruneLedger(
        namespace: SyncNamespace
    ): NetworkResult<PruneResponse> = withContext(Dispatchers.IO) {
        val url = "${getSyncBaseUrl()}/prune".toHttpUrl().newBuilder()
            .addQueryParameter("namespace", namespace.value)
            .build()

        val request = Request.Builder()
            .url(url)
            .delete()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) {
                    NetworkResult.Success(json.decodeFromString<PruneResponse>(body))
                } else {
                    NetworkResult.fromHttp(response.code, body)
                }
            }
        } catch (e: Exception) {
            NetworkResult.fromThrowable(e)
        }
    }
}
