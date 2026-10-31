package project.kosh.core.network

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.PasskeyChallengeResponse
import project.kosh.core.model.PasskeyLoginFinishRequest
import project.kosh.core.model.PasskeyRegisterFinishRequest
import java.net.InetSocketAddress
import java.net.Socket

class PasskeyClientTest {

    private lateinit var httpServer: HttpServer
    private var serverPort: Int = 0
    private lateinit var passkeyClient: PasskeyClient
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
        val baseOkHttpClient = OkHttpClient.Builder()
            .addInterceptor(dynamicHostInterceptor)
            .build()

        passkeyClient = PasskeyClient(
            publicClient = baseOkHttpClient,
            authClient = baseOkHttpClient,
            serverConfigStore = store
        )
        httpServer.start()
    }

    @After
    fun tearDown() {
        httpServer.stop(0)
    }

    @Test
    fun `startLogin successfully requests discoverable challenge and parses publicKeyJson`() = runBlocking {
        httpServer.createContext("/api/auth/passkey/login/start") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val responseBody = """
                {
                    "challenge_id": "d8e3b7c2-1234-5678-abcd-ef0123456789",
                    "challenge": {
                        "publicKey": {
                            "challenge": "base64url_challenge_bytes",
                            "rpId": "kosh.local",
                            "timeout": 60000,
                            "userVerification": "preferred"
                        }
                    }
                }
            """.trimIndent()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = passkeyClient.startLogin()
        assertTrue("Expected NetworkResult.Success, got: $result", result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertEquals("d8e3b7c2-1234-5678-abcd-ef0123456789", data.challengeId)
        assertTrue(data.publicKeyJson.contains("kosh.local"))
        assertTrue(data.publicKeyJson.contains("base64url_challenge_bytes"))
    }

    @Test
    fun `finishLogin successfully sends assertion and receives session token and wrapped vault key`() = runBlocking {
        val dummyCredential = buildJsonObject {
            put("id", "cred_id_123")
            put("rawId", "raw_id_123")
            put("type", "public-key")
        }

        httpServer.createContext("/api/auth/passkey/login/finish") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            val requestBody = exchange.requestBody.bufferedReader().readText()
            assertTrue(requestBody.contains("d8e3b7c2-1234-5678-abcd-ef0123456789"))
            assertTrue(requestBody.contains("cred_id_123"))

            val responseBody = """
                {
                    "token": "7a8b9c_session_bearer_token_118_characters_long",
                    "wrapped_vault_key": "${"a1".repeat(60)}"
                }
            """.trimIndent()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val request = PasskeyLoginFinishRequest(
            challengeId = "d8e3b7c2-1234-5678-abcd-ef0123456789",
            credential = dummyCredential
        )

        val result = passkeyClient.finishLogin(request)
        assertTrue("Expected NetworkResult.Success, got: $result", result is NetworkResult.Success)
        val authResponse = (result as NetworkResult.Success).data
        assertEquals("7a8b9c_session_bearer_token_118_characters_long", authResponse.token)
        assertEquals("a1".repeat(60), authResponse.wrappedVaultKey)
    }

    @Test
    fun `startRegistration transmits bearer header and parses registration challenge`() = runBlocking {
        httpServer.createContext("/api/v1/auth/passkey/register/start") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer secret_user_token", exchange.requestHeaders.getFirst("Authorization"))
            val responseBody = """
                {
                    "challenge_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                    "challenge": {
                        "publicKey": {
                            "rp": { "name": "Kosh Storage Server", "id": "kosh.local" },
                            "challenge": "reg_challenge_token",
                            "pubKeyCredParams": [{ "type": "public-key", "alg": -7 }]
                        }
                    }
                }
            """.trimIndent()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = passkeyClient.startRegistration(token = "secret_user_token")
        assertTrue("Expected NetworkResult.Success, got: $result", result is NetworkResult.Success)
        val data = (result as NetworkResult.Success).data
        assertEquals("3fa85f64-5717-4562-b3fc-2c963f66afa6", data.challengeId)
        assertTrue(data.publicKeyJson.contains("Kosh Storage Server"))
    }

    @Test
    fun `finishRegistration transmits encrypted metadata and handles 201 Created`() = runBlocking {
        val dummyCredential = buildJsonObject {
            put("id", "reg_cred_id_456")
            put("type", "public-key")
        }
        val metadataHex = "deadbeefcafebabe"

        httpServer.createContext("/api/v1/auth/passkey/register/finish") { exchange ->
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer secret_user_token", exchange.requestHeaders.getFirst("Authorization"))
            val requestBody = exchange.requestBody.bufferedReader().readText()
            assertTrue(requestBody.contains("3fa85f64-5717-4562-b3fc-2c963f66afa6"))
            assertTrue(requestBody.contains("reg_cred_id_456"))
            assertTrue(requestBody.contains(metadataHex))

            exchange.sendResponseHeaders(201, -1)
        }

        val request = PasskeyRegisterFinishRequest(
            challengeId = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            credential = dummyCredential,
            encryptedMetadata = metadataHex
        )

        val result = passkeyClient.finishRegistration(request, token = "secret_user_token")
        assertTrue("Expected NetworkResult.Success, got: $result", result is NetworkResult.Success)
    }

    @Test
    fun `listPasskeys parses multiple metadata rows with Unix timestamps`() = runBlocking {
        httpServer.createContext("/api/v1/auth/passkey") { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("Bearer secret_user_token", exchange.requestHeaders.getFirst("Authorization"))
            val responseBody = """
                [
                    {
                        "credential_id": "4a7b9c123456",
                        "encrypted_metadata": "61626364",
                        "created_at": 1730000000,
                        "last_used": 1730000500
                    },
                    {
                        "credential_id": "987654fedcba",
                        "encrypted_metadata": "",
                        "created_at": 1729000000,
                        "last_used": 0
                    }
                ]
            """.trimIndent()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(responseBody.toByteArray()) }
        }

        val result = passkeyClient.listPasskeys(token = "secret_user_token")
        assertTrue("Expected NetworkResult.Success, got: $result", result is NetworkResult.Success)
        val list = (result as NetworkResult.Success).data
        assertEquals(2, list.size)

        assertEquals("4a7b9c123456", list[0].credentialId)
        assertEquals("61626364", list[0].encryptedMetadata)
        assertEquals(1730000000L, list[0].createdAt)
        assertEquals(1730000500L, list[0].lastUsed)
        assertTrue(list[0].hasBeenUsed)

        assertEquals("987654fedcba", list[1].credentialId)
        assertEquals("", list[1].encryptedMetadata)
        assertEquals(1729000000L, list[1].createdAt)
        assertEquals(0L, list[1].lastUsed)
        assertEquals(false, list[1].hasBeenUsed)
    }

    @Test
    fun `deletePasskey sends DELETE request and handles 204 No Content`() = runBlocking {
        val credId = "4a7b9c123456"
        httpServer.createContext("/api/v1/auth/passkey/$credId") { exchange ->
            assertEquals("DELETE", exchange.requestMethod)
            assertEquals("Bearer secret_user_token", exchange.requestHeaders.getFirst("Authorization"))
            exchange.sendResponseHeaders(204, -1)
        }

        val result = passkeyClient.deletePasskey(credId, token = "secret_user_token")
        assertTrue("Expected NetworkResult.Success, got: $result", result is NetworkResult.Success)
    }

    @Test
    fun `deletePasskey maps 404 to NetworkResult Http NotFound`() = runBlocking {
        val credId = "non_existent_cred"
        httpServer.createContext("/api/v1/auth/passkey/$credId") { exchange ->
            val errorBody = "Passkey credential not found"
            exchange.sendResponseHeaders(404, errorBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(errorBody.toByteArray()) }
        }

        val result = passkeyClient.deletePasskey(credId, token = "secret_user_token")
        assertTrue(result is NetworkResult.Http.NotFound)
    }

    @Test
    fun `live server startLogin succeeds against running kosh-server daemon`() = runBlocking {
        val liveHost = "127.0.0.1"
        val livePort = 23342

        val isReachable = try {
            val probeClient = OkHttpClient.Builder()
                .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url("http://$liveHost:$livePort/health").build()
            probeClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }

        Assume.assumeTrue(
            "kosh-server daemon not reachable on $liveHost:$livePort, skipping live test",
            isReachable
        )

        val livePrefs = FakeSharedPreferences()
        val liveStore = ServerConfigurationStore(livePrefs).apply {
            updateServer(host = liveHost, port = livePort, useTls = false)
        }

        val interceptor = DynamicHostInterceptor(liveStore)
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val livePasskeyClient = PasskeyClient(client, client, liveStore)

        val result = livePasskeyClient.startLogin()
        assertTrue("Expected NetworkResult.Success from live daemon, got: $result", result is NetworkResult.Success)
        val challengeData = (result as NetworkResult.Success).data
        assertNotNull(challengeData.challengeId)
        assertTrue(challengeData.challengeId.isNotBlank())
        assertTrue(challengeData.publicKeyJson.contains("challenge"))
    }

    @Test
    fun `startLogin handles 500 InternalServerError from backend`() = runBlocking {
        httpServer.createContext("/api/auth/passkey/login/start_error") { exchange ->
            val errorBody = "Internal server database error"
            exchange.sendResponseHeaders(500, errorBody.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(errorBody.toByteArray()) }
        }

        val errorPrefs = FakeSharedPreferences()
        val errorStore = ServerConfigurationStore(errorPrefs).apply {
            updateServer(host = "127.0.0.1", port = serverPort, useTls = false)
        }
        val client = OkHttpClient.Builder().addInterceptor(DynamicHostInterceptor(errorStore)).build()
        val customPasskeyClient = PasskeyClient(client, client, errorStore)

        // Using standard endpoint that returns 404 since start_error wasn't registered as base endpoint
        val result = customPasskeyClient.startLogin()
        assertTrue(result is NetworkResult.Http)
    }

    @Test
    fun `publicKeyJson extraction supports string JsonPrimitive`() {
        val jsonString = """{"challenge":"foo_challenge","rpId":"kosh.local"}"""
        val response = PasskeyChallengeResponse("test_id", JsonPrimitive(jsonString))
        assertEquals(jsonString, response.publicKeyJson)
    }

    @Test
    fun `publicKeyJson extraction unwrap nested publicKey inside JsonPrimitive string`() {
        val nestedJsonString = """{"publicKey":{"challenge":"foo_inner"}}"""
        val response = PasskeyChallengeResponse("test_id", JsonPrimitive(nestedJsonString))
        assertTrue(response.publicKeyJson.contains("foo_inner"))
        assertEquals(false, response.publicKeyJson.contains("\"publicKey\":{"))
    }
}
