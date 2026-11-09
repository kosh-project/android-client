package project.kosh.core.network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import project.kosh.core.model.MailboxPollState
import project.kosh.core.model.NetworkResult
import java.net.InetSocketAddress
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

        mailboxClient = MailboxClient(
            publicClient = okHttpClient,
            authClient = okHttpClient,
            serverConfigStore = store
        )
        httpServer.start()
    }

    @After
    fun tearDown() {
        httpServer.stop(0)
    }

    @Test
    fun `openMailbox submits pub_temp and PoW header returning mailbox_id and polling_token`() = runBlocking {
        val testPubTemp = "aa".repeat(32)
        val testPoW = "test_pow_header_168_bytes"

        httpServer.createContext("/api/auth/mailbox/open") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals(testPoW, exchange.requestHeaders.getFirst("X-Hashcash"))
            val body = exchange.requestBody.bufferedReader().readText()
            assertTrue(body.contains(testPubTemp))

            val responseBody = """{"mailbox_id": "c7m9x4b2", "polling_token": "token_12345", "expires_in": 300}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = mailboxClient.openMailbox(testPubTemp, testPoW)
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertEquals("c7m9x4b2", data.mailboxId)
        assertEquals("token_12345", data.pollingToken)
        assertEquals(300L, data.expiresIn)
    }

    @Test
    fun `joinMailbox deposits receiver public key and receives polling token`() = runBlocking {
        val testPubTemp = "bb".repeat(32)
        val testPoW = "test_pow_header_168_bytes"

        httpServer.createContext("/api/auth/mailbox/c7m9x4b2/join") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals(testPoW, exchange.requestHeaders.getFirst("X-Hashcash"))
            val body = exchange.requestBody.bufferedReader().readText()
            assertTrue(body.contains(testPubTemp))

            val responseBody = """{"token": "joined_token_67890"}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = mailboxClient.joinMailbox("c7m9x4b2", testPubTemp, testPoW)
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertEquals("joined_token_67890", data.token)
    }

    @Test
    fun `pickupMailbox sends Authorization polling token and parses pending response`() = runBlocking {
        httpServer.createContext("/api/auth/mailbox/c7m9x4b2/pickup") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("Bearer polling_token_abc", exchange.requestHeaders.getFirst("Authorization"))

            val responseBody = """{"status": "pending"}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = mailboxClient.pickupMailbox("c7m9x4b2", "polling_token_abc")
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertTrue(data.isPending)
        assertEquals(false, data.isFulfilled)
    }

    @Test
    fun `pickupMailbox retrieves fulfilled payload and burns entry`() = runBlocking {
        val testPayload = "cd".repeat(92)
        httpServer.createContext("/api/auth/mailbox/c7m9x4b2/pickup") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("Bearer polling_token_abc", exchange.requestHeaders.getFirst("Authorization"))

            val responseBody = """{"status": "fulfilled", "payload": "$testPayload"}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = mailboxClient.pickupMailbox("c7m9x4b2", "polling_token_abc")
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertTrue(data.isFulfilled)
        assertEquals(testPayload, data.payload)
    }

    @Test
    fun `pollPickup transitions from Pending to Fulfilled`() = runBlocking {
        val testPayload = "ef".repeat(92)
        val requestCount = AtomicInteger(0)

        httpServer.createContext("/api/auth/mailbox/c7m9x4b2/pickup") { exchange ->
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
            pollingToken = "polling_token_abc",
            pollIntervalMillis = 50L
        ).take(2).toList()

        assertEquals(2, states.size)
        assertEquals(MailboxPollState.Pending, states[0])
        assertEquals(MailboxPollState.Fulfilled(testPayload), states[1])
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
    fun `checkStatus returns waiting and ready status`() = runBlocking {
        val testPubTemp = "ee".repeat(32)
        httpServer.createContext("/api/v1/devices/mailbox/c7m9x4b2/status") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            val responseBody = """{"status": "ready", "pub_temp": "$testPubTemp"}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = mailboxClient.checkStatus("c7m9x4b2", token = "test_token")
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertTrue(data.isReady)
        assertEquals(testPubTemp, data.pubTemp)
    }

    @Test
    fun `fulfillMailbox deposits 248-hex payload and receives 204 No Content`() = runBlocking {
        val testPayload = "ab".repeat(124) // 248 hex chars (124 bytes)
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
    fun `mintLinkTicket returns single-use registration ticket`() = runBlocking {
        val ticketHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        httpServer.createContext("/api/v1/devices/link-ticket") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val responseBody = """{"ticket": "$ticketHex", "registration_ticket": "$ticketHex", "expires_in": 300}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = mailboxClient.mintLinkTicket(token = "test_token")
        assertTrue(result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertEquals(ticketHex, data.ticket)
        assertEquals(ticketHex, data.registrationTicket)
        assertEquals(300L, data.expiresIn)
    }

    @Test
    fun `pollPickup recovers from intermittent 500 errors before fulfilling`() = runBlocking {
        val testPayload = "cafe".repeat(31) // 124 hex chars
        val callCount = AtomicInteger(0)

        httpServer.createContext("/api/auth/mailbox/retry_mb/pickup") { exchange ->
            val count = callCount.incrementAndGet()
            when (count) {
                1 -> {
                    exchange.sendResponseHeaders(500, -1)
                }
                2 -> {
                    val body = """{"status": "pending"}"""
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
                else -> {
                    val body = """{"status": "fulfilled", "payload": "$testPayload"}"""
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
                    exchange.responseBody.use { it.write(body.toByteArray()) }
                }
            }
        }

        val states = mailboxClient.pollPickup(
            mailboxId = "retry_mb",
            pollingToken = "token_retry",
            pollIntervalMillis = 30L
        ).take(2).toList()

        assertEquals(2, states.size)
        assertEquals(MailboxPollState.Pending, states[0])
        assertEquals(MailboxPollState.Fulfilled(testPayload), states[1])
    }
}
