package project.kosh.core.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address

class KoshDnsTest {

    @After
    fun tearDown() {
        KoshDns.clear()
    }

    @Test
    fun `lookup localhost returns at least one IPv4 address`() {
        val addresses = KoshDns.lookup("localhost")
        assertTrue(addresses.isNotEmpty())
        assertTrue(addresses.first() is Inet4Address)
    }

    @Test
    fun `lookup mapped mDNS hostname returns registered IP address`() {
        KoshDns.setAddress("kosh.local", "192.168.1.5")

        val addresses = KoshDns.lookup("kosh.local")
        assertEquals(1, addresses.size)
        assertEquals("192.168.1.5", addresses.first().hostAddress)

        // Bare name lookup should also resolve
        val bareAddresses = KoshDns.lookup("kosh")
        assertEquals(1, bareAddresses.size)
        assertEquals("192.168.1.5", bareAddresses.first().hostAddress)
    }

    @Test
    fun `getResolvedIp returns cached IP for domain and bare hostname`() {
        KoshDns.setAddress("mynas", "192.168.1.42")
        assertEquals("192.168.1.42", KoshDns.getResolvedIp("mynas"))
        assertEquals("192.168.1.42", KoshDns.getResolvedIp("mynas.local"))
    }

    @Test
    fun `removeAddress unregisters domain from cache`() {
        KoshDns.setAddress("kosh.local", "192.168.1.5")
        assertEquals("192.168.1.5", KoshDns.getResolvedIp("kosh.local"))

        KoshDns.removeAddress("kosh.local")
        assertEquals(null, KoshDns.getResolvedIp("kosh.local"))
    }

    @Test
    fun `setAddress rejects fe80 link-local IPv6 addresses`() {
        KoshDns.setAddress("kosh.local", "fe80::3d0c:a68f:5508:4ae3")
        assertEquals(null, KoshDns.getResolvedIp("kosh.local"))
    }

    @Test
    fun `lookup invalid host throws UnknownHostException`() {
        var threw = false
        try {
            KoshDns.lookup("nonexistent.invalid.kosh.domain")
        } catch (_: java.net.UnknownHostException) {
            threw = true
        }
        assertTrue(threw)
    }
}
