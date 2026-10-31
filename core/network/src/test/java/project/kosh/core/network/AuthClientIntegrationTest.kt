package project.kosh.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import project.kosh.core.model.NetworkResult
import java.net.Socket

class AuthClientIntegrationTest {

    private fun isServerReachable(host: String, port: Int): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url("http://$host:$port/health").build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    @Test
    fun `live server fetchChallenge succeeds against running background koshd daemon`() = runBlocking {
        val serverPort = 23342
        val serverHost = "127.0.0.1"

        // Skip test gracefully if background server is not running in the environment
        Assume.assumeTrue(
            "kosh-server is not reachable on $serverHost:$serverPort, skipping live test",
            isServerReachable(serverHost, serverPort)
        )

        val fakePrefs = FakeSharedPreferences()
        val store = ServerConfigurationStore(fakePrefs).apply {
            updateServer(
                host = serverHost,
                port = serverPort,
                useTls = false
            )
        }

        val dynamicHostInterceptor = DynamicHostInterceptor(store)
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(dynamicHostInterceptor)
            .build()

        val authClient = AuthClient(okHttpClient, store)
        val result = authClient.fetchChallenge("login")

        assertTrue("Expected NetworkResult.Success from live server, got: $result", result is NetworkResult.Success)
        val challengeData = (result as NetworkResult.Success).data
        assertNotNull(challengeData.challenge)
        assertTrue(challengeData.challenge.endsWith("login"))
    }
}
