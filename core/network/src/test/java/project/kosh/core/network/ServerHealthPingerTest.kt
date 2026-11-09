package project.kosh.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerHealthPingerTest {

    private val pinger = ServerHealthPinger()

    @Test
    fun `ping invalid host or port returns failure immediately`() = runBlocking {
        val resultBlank = pinger.ping("", 23342, false)
        assertTrue(resultBlank is PingResult.Failure)
        assertEquals("Invalid host or port", (resultBlank as PingResult.Failure).error)

        val resultPort = pinger.ping("127.0.0.1", -1, true)
        assertTrue(resultPort is PingResult.Failure)
        assertEquals("Invalid host or port", (resultPort as PingResult.Failure).error)
    }

    @Test
    fun `ping unroutable port returns failure with clean diagnostic`() = runBlocking {
        // Port 1 is normally closed on localhost
        val result = pinger.ping("127.0.0.1", 1, false)
        assertTrue(result is PingResult.Failure)
        val failure = result as PingResult.Failure
        assertTrue(failure.error.isNotEmpty())
    }

    @Test
    fun `ping live server with TLS succeeds if running`() = runBlocking {
        // If the live TLS server is active on 23342, test actual latency measurement
        val isReachable = try {
            java.net.Socket("127.0.0.1", 23342).use { true }
        } catch (_: Exception) {
            false
        }

        if (isReachable) {
            val result = pinger.ping("127.0.0.1", 23342, useTls = true)
            assertTrue(result is PingResult.Success)
            val success = result as PingResult.Success
            assertEquals(200, success.statusCode)
            assertTrue(success.latencyMs >= 0)
            assertTrue(success.isTls)
        }
    }
}
