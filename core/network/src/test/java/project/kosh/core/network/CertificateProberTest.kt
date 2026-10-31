package project.kosh.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateProberTest {

    private val prober = CertificateProber()

    @Test
    fun `probeServer with invalid host or port fails immediately`() = runBlocking {
        val resultBlank = prober.probeServer("", 23342, useTls = true)
        assertTrue(resultBlank.isFailure)

        val resultPort = prober.probeServer("127.0.0.1", 99999, useTls = true)
        assertTrue(resultPort.isFailure)
    }

    @Test
    fun `probeServer with unroutable port fails cleanly`() = runBlocking {
        val result = prober.probeServer("127.0.0.1", 1, useTls = true)
        assertTrue(result.isFailure)
    }

    @Test
    fun `probeServer on running TLS server extracts valid 32-byte fingerprint and SAS words`() = runBlocking {
        val isReachable = try {
            java.net.Socket("127.0.0.1", 23342).use { true }
        } catch (_: Exception) {
            false
        }

        if (isReachable) {
            val result = prober.probeServer("127.0.0.1", 23342, useTls = true)
            assertTrue(result.isSuccess)
            val probe = result.getOrThrow()
            assertTrue(probe.isTls)
            assertEquals(32, probe.fingerprintBytes.size)
            assertEquals(64, probe.fingerprintHex.length)
            assertEquals(12, probe.sasWords.size)
            assertNotNull(probe.subject)
        }
    }
}
