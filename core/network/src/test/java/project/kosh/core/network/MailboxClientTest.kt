package project.kosh.core.network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import project.kosh.core.model.MailboxPollState
import project.kosh.core.model.NetworkResult
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class MailboxClientTest {

    private lateinit var httpServer: HttpServer
    private var serverPort: Int = 0
    private lateinit var mailboxClient: MailboxClient
    private lateinit var store: ServerConfigurationStore

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

        mailboxClient = MailboxClient(okHttpClient, store)
        httpServer.start()
    }

    @After
    fun tearDown() {
        httpServer.stop(0)
    }

    @Test
    fun `allocateMailbox successfully parses 8-char Crockford ID and 300s TTL`() = runBlocking {
        httpServer.createContext("/api/v1/devices/mailbox") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val responseBody = """{"mailbox_id": "c7m9x4b2", "expires_in": 300}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = mailboxClient.allocateMailbox(token = "test_token")
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertEquals("c7m9x4b2", data.mailboxId)
        assertEquals(300L, data.expiresIn)
    }

    @Test
    fun `fulfillMailbox deposits 184-hex payload and receives 204 No Content`() = runBlocking {
        val testPayload = "ab".repeat(92) // 184 hex chars (92 bytes)
        httpServer.createContext("/api/v1/devices/mailbox/c7m9x4b2/fulfill") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val body = exchange.requestBody.bufferedReader().readText()
            assertTrue(body.contains(testPayload))
            exchange.sendResponseHeaders(204, -1)
        }

        val result = mailboxClient.fulfillMailbox("c7m9x4b2", testPayload, token = "test_token")
        assertTrue(result is NetworkResult.Success)
    }

    @Test
    fun `pickupMailbox returns pending status when not yet fulfilled`() = runBlocking {
        httpServer.createContext("/api/v1/devices/mailbox/c7m9x4b2") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            val responseBody = """{"status": "pending"}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = mailboxClient.pickupMailbox("c7m9x4b2", token = "test_token")
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertTrue(data.isPending)
        assertEquals(false, data.isFulfilled)
    }

    @Test
    fun `pickupMailbox returns fulfilled status and burns payload`() = runBlocking {
        val testPayload = "cd".repeat(92)
        httpServer.createContext("/api/v1/devices/mailbox/c7m9x4b2") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            val responseBody = """{"status": "fulfilled", "payload": "$testPayload"}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = mailboxClient.pickupMailbox("c7m9x4b2", token = "test_token")
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertTrue(data.isFulfilled)
        assertEquals(testPayload, data.payload)
    }

    @Test
    fun `pollPickup transitions from Pending to Fulfilled`() = runBlocking {
        val testPayload = "ef".repeat(92)
        val requestCount = AtomicInteger(0)

        httpServer.createContext("/api/v1/devices/mailbox/c7m9x4b2") { exchange ->
            val count = requestCount.incrementAndGet()
            val responseBody = if (count == 1) {
                """{"status": "pending"}"""
            } else {
                """{"status": "fulfilled", "payload": "$testPayload"}"""
            }
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val states = mailboxClient.pollPickup(
            mailboxId = "c7m9x4b2",
            token = "test_token",
            pollIntervalMillis = 50L
        ).take(2).toList()

        assertEquals(2, states.size)
        assertEquals(MailboxPollState.Pending, states[0])
        assertEquals(MailboxPollState.Fulfilled(testPayload), states[1])
    }

    @Test
    fun `live koshd daemon rejects unauthenticated mailbox access with 401 Unauthorized`() = runBlocking {
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
        val liveClient = MailboxClient(
            OkHttpClient.Builder().addInterceptor(DynamicHostInterceptor(liveStore)).build(),
            liveStore
        )

        // Attempting to create mailbox without Authorization header
        val result = liveClient.allocateMailbox()
        assertTrue("Expected 401 Unauthorized from live daemon, got: $result", result is NetworkResult.Http.Unauthorized)
    }
}
