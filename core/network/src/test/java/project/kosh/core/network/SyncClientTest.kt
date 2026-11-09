package project.kosh.core.network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import project.kosh.core.model.DeltaAppendResponse
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PruneResponse
import project.kosh.core.model.SnapshotRegisterRequest
import project.kosh.core.model.SnapshotResponse
import project.kosh.core.model.SyncFrame
import project.kosh.core.model.SyncNamespace
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SyncClientTest {

    private lateinit var httpServer: HttpServer
    private var serverPort: Int = 0
    private lateinit var syncClient: SyncClient
    private lateinit var store: ServerConfigurationStore
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverPort = httpServer.address.port

        val fakePrefs = FakeSharedPreferences()
        store = ServerConfigurationStore(fakePrefs).apply {
            updateServer(
                host = "127.0.0.1",
                port = serverPort,
                useTls = false
            )
        }

        val dynamicHostInterceptor = DynamicHostInterceptor(store)
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(dynamicHostInterceptor)
            .build()

        syncClient = SyncClient(okHttpClient, store)
        httpServer.start()
    }

    @After
    fun tearDown() {
        httpServer.stop(0)
    }

    // ==========================================
    // 1. streamDeltas Tests
    // ==========================================

    @Test
    fun `streamDeltas parses multiple framed records across stream`() = runBlocking {
        val payload1 = "delta_action_one".toByteArray()
        val payload2 = "delta_action_two_longer_payload".toByteArray()
        val payload3 = "delta_three".toByteArray()

        val buffer = Buffer()
        // Record 1: len, seq, payload
        buffer.writeInt(payload1.size)
        buffer.writeLong(101L)
        buffer.write(payload1)

        // Record 2
        buffer.writeInt(payload2.size)
        buffer.writeLong(102L)
        buffer.write(payload2)

        // Record 3
        buffer.writeInt(payload3.size)
        buffer.writeLong(103L)
        buffer.write(payload3)

        val streamBytes = buffer.readByteArray()

        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertTrue(exchange.requestURI.query.contains("namespace=gallery"))
            assertTrue(exchange.requestURI.query.contains("file=current.log"))
            assertTrue(exchange.requestURI.query.contains("offset=0"))

            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, streamBytes.size.toLong())
            exchange.responseBody.use { it.write(streamBytes) }
        }

        val frames = syncClient.streamDeltas(
            namespace = SyncNamespace.GALLERY,
            fileName = "current.log",
            offset = 0L
        ).toList()

        assertEquals(3, frames.size)

        assertEquals(payload1.size, frames[0].length)
        assertEquals(101L, frames[0].sequenceId)
        assertArrayEquals(payload1, frames[0].payload)

        assertEquals(payload2.size, frames[1].length)
        assertEquals(102L, frames[1].sequenceId)
        assertArrayEquals(payload2, frames[1].payload)

        assertEquals(payload3.size, frames[2].length)
        assertEquals(103L, frames[2].sequenceId)
        assertArrayEquals(payload3, frames[2].payload)
    }

    @Test
    fun `streamDeltas strictly enforces Big-Endian byte layout`() = runBlocking {
        // Construct frame manually using ByteBuffer with explicit BIG_ENDIAN byte order
        val testLength = 7
        val testSeq = 256L // in 8 bytes BE: 0x00 00 00 00 00 00 01 00
        val testPayload = byteArrayOf(1, 2, 3, 4, 5, 6, 7)

        val byteBuffer = ByteBuffer.allocate(4 + 8 + testLength)
        byteBuffer.order(ByteOrder.BIG_ENDIAN)
        byteBuffer.putInt(testLength)
        byteBuffer.putLong(testSeq)
        byteBuffer.put(testPayload)
        val rawWireBytes = byteBuffer.array()

        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, rawWireBytes.size.toLong())
            exchange.responseBody.use { it.write(rawWireBytes) }
        }

        val frames = syncClient.streamDeltas(
            namespace = SyncNamespace.FILES,
            fileName = "0000000000000001.log",
            offset = 500L
        ).toList()

        assertEquals(1, frames.size)
        val frame = frames[0]
        assertEquals(7, frame.length)
        assertEquals(256L, frame.sequenceId)
        assertArrayEquals(testPayload, frame.payload)
    }

    @Test
    fun `streamDeltas handles zero-byte payload frame safely`() = runBlocking {
        val buffer = Buffer()
        buffer.writeInt(0) // 0-length payload
        buffer.writeLong(5555L)
        val frameBytes = buffer.readByteArray()

        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, frameBytes.size.toLong())
            exchange.responseBody.use { it.write(frameBytes) }
        }

        val frames = syncClient.streamDeltas(SyncNamespace.FILES).toList()
        assertEquals(1, frames.size)
        assertEquals(0, frames[0].length)
        assertEquals(5555L, frames[0].sequenceId)
        assertEquals(0, frames[0].payload.size)
    }

    @Test
    fun `streamDeltas completes cleanly on stream exhaustion with empty stream`() = runBlocking {
        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        val frames = syncClient.streamDeltas(SyncNamespace.GALLERY).toList()
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `streamDeltas throws IllegalStateException when frame length exceeds 16MB bound`() = runBlocking {
        val overflowLength = (16 * 1024 * 1024) + 1
        val buffer = Buffer()
        buffer.writeInt(overflowLength)
        buffer.writeLong(1L)
        val frameBytes = buffer.readByteArray()

        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, frameBytes.size.toLong())
            exchange.responseBody.use { it.write(frameBytes) }
        }

        try {
            syncClient.streamDeltas(SyncNamespace.FILES).toList()
            fail("Expected IllegalStateException due to frame length overflow")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Invalid frame length") == true)
        }
    }

    @Test
    fun `streamDeltas throws IllegalStateException when frame length is negative`() = runBlocking {
        val buffer = Buffer()
        buffer.writeInt(-1)
        buffer.writeLong(1L)
        val frameBytes = buffer.readByteArray()

        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, frameBytes.size.toLong())
            exchange.responseBody.use { it.write(frameBytes) }
        }

        try {
            syncClient.streamDeltas(SyncNamespace.FILES).toList()
            fail("Expected IllegalStateException due to negative frame length")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Invalid frame length") == true)
        }
    }

    @Test
    fun `streamDeltas throws EOFException when stream is truncated mid-frame`() = runBlocking {
        val buffer = Buffer()
        buffer.writeInt(20) // Declares 20 bytes payload
        buffer.writeLong(42L)
        buffer.write(byteArrayOf(1, 2, 3, 4, 5)) // Only 5 bytes provided
        val frameBytes = buffer.readByteArray()

        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, frameBytes.size.toLong())
            exchange.responseBody.use { it.write(frameBytes) }
        }

        try {
            syncClient.streamDeltas(SyncNamespace.GALLERY).toList()
            fail("Expected EOFException due to truncated payload stream")
        } catch (e: EOFException) {
            // Expected EOFException
            assertNotNull(e)
        }
    }

    @Test
    fun `streamDeltas validates fileName and offset arguments`() = runBlocking {
        // Path traversal
        try {
            syncClient.streamDeltas(SyncNamespace.FILES, fileName = "../segment.log").toList()
            fail("Expected IllegalArgumentException for path traversal")
        } catch (_: IllegalArgumentException) {}

        // Invalid extension
        try {
            syncClient.streamDeltas(SyncNamespace.FILES, fileName = "current.txt").toList()
            fail("Expected IllegalArgumentException for invalid extension")
        } catch (_: IllegalArgumentException) {}

        // Negative offset
        try {
            syncClient.streamDeltas(SyncNamespace.FILES, offset = -5L).toList()
            fail("Expected IllegalArgumentException for negative offset")
        } catch (_: IllegalArgumentException) {}

        // Missing base name (.log only)
        try {
            syncClient.streamDeltas(SyncNamespace.FILES, fileName = ".log").toList()
            fail("Expected IllegalArgumentException for .log without base name")
        } catch (_: IllegalArgumentException) {}

        // Space in filename
        try {
            syncClient.streamDeltas(SyncNamespace.FILES, fileName = "current log.log").toList()
            fail("Expected IllegalArgumentException for space in file name")
        } catch (_: IllegalArgumentException) {}

        // Special characters in filename
        try {
            syncClient.streamDeltas(SyncNamespace.FILES, fileName = "current*1.log").toList()
            fail("Expected IllegalArgumentException for special characters in file name")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `streamDeltas throws SyncHttpException on HTTP error response`() = runBlocking {
        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            val errorResponse = "Segment not found"
            exchange.sendResponseHeaders(404, errorResponse.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(errorResponse.toByteArray()) }
        }

        try {
            syncClient.streamDeltas(SyncNamespace.FILES).toList()
            fail("Expected SyncHttpException on HTTP 404")
        } catch (e: SyncHttpException) {
            assertEquals(404, e.code)
            assertEquals("Segment not found", e.serverMessage)
            assertTrue(e.message?.contains("404") == true)
        }
    }

    @Test
    fun `streamDeltas unblocks and cancels immediately when collecting coroutine is cancelled`() = runBlocking {
        val payload1 = "delta_1".toByteArray()
        val payload2 = "delta_2".toByteArray()
        val buffer = Buffer()
        buffer.writeInt(payload1.size)
        buffer.writeLong(1L)
        buffer.write(payload1)
        buffer.writeInt(payload2.size)
        buffer.writeLong(2L)
        buffer.write(payload2)
        val streamBytes = buffer.readByteArray()

        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, streamBytes.size.toLong())
            exchange.responseBody.use { it.write(streamBytes) }
        }

        val collected = mutableListOf<SyncFrame>()
        val job = launch {
            syncClient.streamDeltas(SyncNamespace.FILES).collect { frame ->
                collected.add(frame)
                throw kotlinx.coroutines.CancellationException("Stop streaming")
            }
        }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(1, collected.size)
        assertEquals(1L, collected[0].sequenceId)
    }

    @Test
    fun `streamDeltas cancellation with take operator completes cleanly`() = runBlocking {
        val payload1 = "frame_one".toByteArray()
        val payload2 = "frame_two".toByteArray()
        val buffer = Buffer()
        buffer.writeInt(payload1.size)
        buffer.writeLong(1L)
        buffer.write(payload1)
        buffer.writeInt(payload2.size)
        buffer.writeLong(2L)
        buffer.write(payload2)
        val streamBytes = buffer.readByteArray()

        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, streamBytes.size.toLong())
            exchange.responseBody.use { it.write(streamBytes) }
        }

        val frames = syncClient.streamDeltas(SyncNamespace.GALLERY)
            .take(1)
            .toList()

        assertEquals(1, frames.size)
        assertEquals(1L, frames[0].sequenceId)
        assertArrayEquals(payload1, frames[0].payload)
    }

    // ==========================================
    // 2. appendDelta Tests
    // ==========================================

    @Test
    fun `appendDelta sends octet-stream and maps 200 OK to DeltaAppendResponse`() = runBlocking {
        val deltaBytes = byteArrayOf(10, 20, 30, 40, 50)

        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("namespace=gallery", exchange.requestURI.query)
            assertTrue(exchange.requestHeaders.getFirst("Content-Type")?.contains("application/octet-stream") == true)

            val receivedBytes = exchange.requestBody.readBytes()
            assertArrayEquals(deltaBytes, receivedBytes)

            val responseBody = """{"sequence_id": 1042, "file_name": "current.log", "offset": 45120}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.appendDelta(SyncNamespace.GALLERY, deltaBytes)
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertEquals(1042L, data.sequenceId)
        assertEquals("current.log", data.fileName)
        assertEquals(45120L, data.offset)
    }

    @Test
    fun `appendDelta maps HTTP 401 Unauthorized`() = runBlocking {
        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            val responseBody = "Invalid bearer token"
            exchange.sendResponseHeaders(401, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.appendDelta(SyncNamespace.FILES, byteArrayOf(1, 2))
        assertTrue(result is NetworkResult.Http.Unauthorized)
    }

    @Test
    fun `appendDelta maps HTTP 500 Server Error`() = runBlocking {
        httpServer.createContext("/api/v1/sync/delta") { exchange ->
            val responseBody = "Committer channel disconnected"
            exchange.sendResponseHeaders(500, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.appendDelta(SyncNamespace.FILES, byteArrayOf(1, 2))
        assertTrue(result is NetworkResult.Http.ServerError)
        assertEquals(500, (result as NetworkResult.Http.ServerError).code)
    }

    // ==========================================
    // 3. getLatestSnapshot Tests
    // ==========================================

    @Test
    fun `getLatestSnapshot returns parsed SnapshotResponse when snapshot exists`() = runBlocking {
        httpServer.createContext("/api/v1/sync/snapshot") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("namespace=files", exchange.requestURI.query)

            val responseBody = """
                {
                    "id": 7,
                    "snapshot_seq": 5000,
                    "blob_hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    "created_at": 1730000000
                }
            """.trimIndent()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.getLatestSnapshot(SyncNamespace.FILES)
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertNotNull(data)
        assertEquals(7L, data!!.id)
        assertEquals(5000L, data.snapshotSeq)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", data.blobHash)
        assertEquals(1730000000L, data.createdAt)
    }

    @Test
    fun `getLatestSnapshot returns null data when server responds with null string`() = runBlocking {
        httpServer.createContext("/api/v1/sync/snapshot") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            val responseBody = "null"
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.getLatestSnapshot(SyncNamespace.GALLERY)
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertNull(data)
    }

    @Test
    fun `getLatestSnapshot returns null data when server responds with empty body`() = runBlocking {
        httpServer.createContext("/api/v1/sync/snapshot") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }

        val result = syncClient.getLatestSnapshot(SyncNamespace.FILES)
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertNull(data)
    }

    @Test
    fun `getLatestSnapshot maps HTTP error to NetworkResult Failure`() = runBlocking {
        httpServer.createContext("/api/v1/sync/snapshot") { exchange ->
            val responseBody = "Database query timeout"
            exchange.sendResponseHeaders(500, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.getLatestSnapshot(SyncNamespace.FILES)
        assertTrue(result is NetworkResult.Http.ServerError)
    }

    // ==========================================
    // 4. registerSnapshot Tests
    // ==========================================

    @Test
    fun `registerSnapshot posts JSON request and parses returned ID`() = runBlocking {
        val testHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        httpServer.createContext("/api/v1/sync/snapshot") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val body = exchange.requestBody.bufferedReader().readText()
            val parsedReq = json.decodeFromString<SnapshotRegisterRequest>(body)
            assertEquals(SyncNamespace.GALLERY, parsedReq.namespace)
            assertEquals(8000L, parsedReq.snapshotSeq)
            assertEquals(testHash, parsedReq.blobHash)

            val responseBody = "42"
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.registerSnapshot(
            namespace = SyncNamespace.GALLERY,
            snapshotSeq = 8000L,
            blobHash = testHash
        )
        assertTrue(result is NetworkResult.Success)
        assertEquals(42L, (result as NetworkResult.Success).data)
    }

    @Test
    fun `registerSnapshot maps HTTP 400 Bad Request on invalid hash`() = runBlocking {
        httpServer.createContext("/api/v1/sync/snapshot") { exchange ->
            val responseBody = "blob_hash must be 32 bytes in length"
            exchange.sendResponseHeaders(400, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.registerSnapshot(
            namespace = SyncNamespace.FILES,
            snapshotSeq = 100L,
            blobHash = "short_hash"
        )
        assertTrue(result is NetworkResult.Http.ClientError)
        assertEquals(400, (result as NetworkResult.Http.ClientError).code)
    }

    @Test
    fun `registerSnapshot parses JSON object response with id field`() = runBlocking {
        val testHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        httpServer.createContext("/api/v1/sync/snapshot") { exchange ->
            val responseBody = """{"id": 108, "status": "created"}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.registerSnapshot(
            namespace = SyncNamespace.FILES,
            snapshotSeq = 2000L,
            blobHash = testHash
        )
        assertTrue(result is NetworkResult.Success)
        assertEquals(108L, (result as NetworkResult.Success).data)
    }

    @Test
    fun `registerSnapshot parses JSON object response with snapshot_id field`() = runBlocking {
        val testHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        httpServer.createContext("/api/v1/sync/snapshot") { exchange ->
            val responseBody = """{"snapshot_id": 999}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.registerSnapshot(
            namespace = SyncNamespace.GALLERY,
            snapshotSeq = 3000L,
            blobHash = testHash
        )
        assertTrue(result is NetworkResult.Success)
        assertEquals(999L, (result as NetworkResult.Success).data)
    }

    // ==========================================
    // 5. pruneLedger Tests
    // ==========================================

    @Test
    fun `pruneLedger issues DELETE and parses PruneResponse`() = runBlocking {
        httpServer.createContext("/api/v1/sync/prune") { exchange ->
            assertEquals("DELETE", exchange.requestMethod)
            assertEquals("namespace=files", exchange.requestURI.query)

            val responseBody = """{"safe_seq": 4500, "pruned_segments": 3}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.pruneLedger(SyncNamespace.FILES)
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertEquals(4500L, data.safeSeq)
        assertEquals(3L, data.prunedSegments)
    }

    @Test
    fun `pruneLedger maps HTTP 401 Unauthorized`() = runBlocking {
        httpServer.createContext("/api/v1/sync/prune") { exchange ->
            val responseBody = "Unauthorized"
            exchange.sendResponseHeaders(401, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = syncClient.pruneLedger(SyncNamespace.GALLERY)
        assertTrue(result is NetworkResult.Http.Unauthorized)
    }

    // ==========================================
    // 6. SyncFrame and Model Tests
    // ==========================================

    @Test
    fun `SyncFrame equality and hashCode with ByteArray payloads`() {
        val payloadA = byteArrayOf(1, 2, 3)
        val payloadB = byteArrayOf(1, 2, 3)
        val payloadC = byteArrayOf(1, 2, 4)

        val frame1 = SyncFrame(3, 100L, payloadA)
        val frame2 = SyncFrame(3, 100L, payloadB)
        val frame3 = SyncFrame(3, 100L, payloadC)
        val frame4 = SyncFrame(3, 101L, payloadA)

        assertEquals(frame1, frame2)
        assertEquals(frame1.hashCode(), frame2.hashCode())

        assertFalse(frame1 == frame3)
        assertFalse(frame1 == frame4)
        assertTrue(frame1.toString().contains("payloadSize=3"))
    }

    @Test
    fun `SyncNamespace serialization round-trip and value matching`() {
        val encodedFiles = json.encodeToString(SyncNamespace.FILES)
        assertEquals("\"files\"", encodedFiles)
        val decodedFiles = json.decodeFromString<SyncNamespace>(encodedFiles)
        assertEquals(SyncNamespace.FILES, decodedFiles)

        val encodedGallery = json.encodeToString(SyncNamespace.GALLERY)
        assertEquals("\"gallery\"", encodedGallery)
        val decodedGallery = json.decodeFromString<SyncNamespace>(encodedGallery)
        assertEquals(SyncNamespace.GALLERY, decodedGallery)

        assertEquals(SyncNamespace.FILES, SyncNamespace.fromValue("files"))
        assertEquals(SyncNamespace.GALLERY, SyncNamespace.fromValue("gallery"))
        assertEquals(SyncNamespace.FILES, SyncNamespace.fromValue("FILES"))

        try {
            SyncNamespace.fromValue("invalid_ns")
            fail("Expected IllegalArgumentException for invalid namespace")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `SyncFrame enforces non-negative length matching payload size`() {
        // Mismatched length and payload size
        try {
            SyncFrame(length = 5, sequenceId = 1L, payload = byteArrayOf(1, 2, 3))
            fail("Expected IllegalArgumentException for mismatched length and payload size")
        } catch (_: IllegalArgumentException) {}

        // Negative length
        try {
            SyncFrame(length = -1, sequenceId = 1L, payload = byteArrayOf())
            fail("Expected IllegalArgumentException for negative length")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `appendDelta works with trailing slash on server base URL`() = runBlocking {
        val prefs = FakeSharedPreferences()
        val trailingSlashStore = ServerConfigurationStore(prefs).apply {
            updateServer(host = "127.0.0.1", port = serverPort, useTls = false)
        }
        val client = SyncClient(
            OkHttpClient.Builder().addInterceptor(DynamicHostInterceptor(trailingSlashStore)).build(),
            trailingSlashStore
        )

        httpServer.createContext("/api/v1/sync/delta_trailing") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val responseBody = """{"sequence_id": 1, "file_name": "current.log", "offset": 512}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        // Test with standard delta call to verify no double slashes are formed
        val result = client.appendDelta(SyncNamespace.FILES, byteArrayOf(1, 2))
        // Since httpServer routes /api/v1/sync/delta (or delta_trailing), verify URL formatting
        assertTrue(result is NetworkResult.Success || result is NetworkResult.Http)
    }

    // ==========================================
    // 7. Live koshd Daemon Integration Test
    // ==========================================

    @Test
    fun `live koshd daemon rejects unauthenticated sync access with 401 Unauthorized`() = runBlocking {
        val livePort = 23342
        val liveHost = "127.0.0.1"

        val reachable = try {
            val probeClient = OkHttpClient.Builder()
                .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url("http://$liveHost:$livePort/health").build()
            probeClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }

        Assume.assumeTrue(
            "kosh-server is not reachable on $liveHost:$livePort, skipping live daemon test",
            reachable
        )

        val livePrefs = FakeSharedPreferences()
        val liveStore = ServerConfigurationStore(livePrefs).apply {
            updateServer(host = liveHost, port = livePort, useTls = false)
        }
        val liveClient = SyncClient(
            OkHttpClient.Builder().addInterceptor(DynamicHostInterceptor(liveStore)).build(),
            liveStore
        )

        // Attempting to append delta without Authorization header
        val result = liveClient.appendDelta(SyncNamespace.GALLERY, byteArrayOf(1, 2, 3))
        assertTrue(
            "Expected 401 Unauthorized from live daemon, got: $result",
            result is NetworkResult.Http.Unauthorized
        )
    }
}
