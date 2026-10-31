package project.kosh.core.network

import android.content.Context
import android.net.nsd.NsdServiceInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

@Suppress("DEPRECATION")
class MdnsDiscoveryManagerTest {

    private lateinit var mockContext: Context
    private lateinit var discoveryManager: MdnsDiscoveryManager

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        discoveryManager = MdnsDiscoveryManager(mockContext)
    }

    @Test
    fun `parseServiceInfo extracts IPv4 host, port, and DNS-SD TXT records for HTTPS`() {
        val serviceInfo = mockk<NsdServiceInfo>()
        val inetAddress = InetAddress.getByName("192.168.1.150")

        every { serviceInfo.serviceName } returns "kosh-server"
        every { serviceInfo.host } returns inetAddress
        every { serviceInfo.port } returns 6969
        every { serviceInfo.serviceType } returns "_https._tcp."
        every { serviceInfo.attributes } returns mapOf(
            "tls" to "true".toByteArray(Charsets.UTF_8),
            "version" to "1.0.0".toByteArray(Charsets.UTF_8),
            "hostname" to "kosh-server".toByteArray(Charsets.UTF_8)
        )

        val server = discoveryManager.parseServiceInfo(serviceInfo)
        assertNotNull(server)

        assertEquals("kosh-server", server!!.name)
        assertEquals("192.168.1.150", server.host)
        assertEquals(6969, server.port)
        assertTrue(server.isTls)
        assertEquals("1.0.0", server.version)
        assertEquals("kosh-server", server.hostname)
        assertEquals("https://192.168.1.150:6969", server.baseUrl)
        assertEquals("true", server.txtRecords["tls"])
    }

    @Test
    fun `parseServiceInfo detects plain HTTP service and constructs http baseUrl`() {
        val serviceInfo = mockk<NsdServiceInfo>()
        val inetAddress = InetAddress.getByName("10.0.2.2")

        every { serviceInfo.serviceName } returns "kosh-dev"
        every { serviceInfo.host } returns inetAddress
        every { serviceInfo.port } returns 23342
        every { serviceInfo.serviceType } returns "_http._tcp."
        every { serviceInfo.attributes } returns mapOf(
            "tls" to "false".toByteArray(Charsets.UTF_8),
            "version" to "0.9.0".toByteArray(Charsets.UTF_8)
        )

        val server = discoveryManager.parseServiceInfo(serviceInfo)
        assertNotNull(server)

        assertEquals("10.0.2.2", server!!.host)
        assertEquals(23342, server.port)
        assertFalse(server.isTls)
        assertEquals("http://10.0.2.2:23342", server.baseUrl)
    }

    @Test
    fun `parseServiceInfo handles missing attributes gracefully`() {
        val serviceInfo = mockk<NsdServiceInfo>()
        val inetAddress = InetAddress.getByName("127.0.0.1")

        every { serviceInfo.serviceName } returns null
        every { serviceInfo.host } returns inetAddress
        every { serviceInfo.port } returns 6969
        every { serviceInfo.serviceType } returns null
        every { serviceInfo.attributes } returns emptyMap()

        val server = discoveryManager.parseServiceInfo(serviceInfo)
        assertNotNull(server)

        assertEquals("kosh-server", server!!.name)
        assertEquals("127.0.0.1", server.host)
        assertFalse(server.isTls)
        assertEquals("http://127.0.0.1:6969", server.baseUrl)
    }

    @Test
    fun `parseServiceInfo formats IPv6 literal address in baseUrl with brackets`() {
        val serviceInfo = mockk<NsdServiceInfo>()
        val inetAddress = InetAddress.getByName("::1")

        every { serviceInfo.serviceName } returns "kosh-ipv6"
        every { serviceInfo.host } returns inetAddress
        every { serviceInfo.port } returns 6969
        every { serviceInfo.serviceType } returns "_https._tcp."
        every { serviceInfo.attributes } returns emptyMap()

        val server = discoveryManager.parseServiceInfo(serviceInfo)
        assertNotNull(server)

        assertEquals(inetAddress.hostAddress, server!!.host)
        assertEquals("https://[${inetAddress.hostAddress}]:6969", server.baseUrl)
    }

    @Test
    fun `parseServiceInfo falls back to canonical default port when advertised port is invalid`() {
        val serviceInfo = mockk<NsdServiceInfo>()
        val inetAddress = InetAddress.getByName("192.168.1.100")

        every { serviceInfo.serviceName } returns "kosh-server"
        every { serviceInfo.host } returns inetAddress
        every { serviceInfo.port } returns 0
        every { serviceInfo.serviceType } returns "_http._tcp."
        every { serviceInfo.attributes } returns emptyMap()

        val server = discoveryManager.parseServiceInfo(serviceInfo)
        assertNotNull(server)

        assertEquals(ServerConfig.DEFAULT_PORT, server!!.port)
    }

    @Test
    fun `acquireMulticastLock and releaseMulticastLock manage reference count safely`() {
        val mockWifiManager = mockk<android.net.wifi.WifiManager>()
        val mockLock = mockk<android.net.wifi.WifiManager.MulticastLock>(relaxed = true)

        every { mockContext.applicationContext.getSystemService(Context.WIFI_SERVICE) } returns mockWifiManager
        every { mockWifiManager.createMulticastLock(any()) } returns mockLock
        every { mockLock.isHeld } returns true

        val manager = MdnsDiscoveryManager(mockContext)

        manager.acquireMulticastLock()
        manager.acquireMulticastLock()

        io.mockk.verify(exactly = 1) { mockLock.acquire() }

        manager.releaseMulticastLock()
        io.mockk.verify(exactly = 0) { mockLock.release() }

        manager.releaseMulticastLock()
        io.mockk.verify(exactly = 1) { mockLock.release() }
    }

    @Test
    fun `parseServiceInfo prefers IPv4 address over IPv6 link-local when both are present`() {
        val serviceInfo = mockk<NsdServiceInfo>()
        val ipv6LinkLocal = InetAddress.getByName("fe80::3d0c:a68f:5508:4ae3")
        val ipv4Lan = InetAddress.getByName("192.168.1.5")

        every { serviceInfo.serviceName } returns "kosh-server"
        every { serviceInfo.host } returns ipv6LinkLocal
        every { serviceInfo.hostAddresses } returns listOf(ipv6LinkLocal, ipv4Lan)
        every { serviceInfo.port } returns 23342
        every { serviceInfo.serviceType } returns "_http._tcp."
        every { serviceInfo.attributes } returns emptyMap()

        val server = discoveryManager.parseServiceInfo(serviceInfo)
        assertNotNull(server)

        assertEquals("192.168.1.5", server!!.host)
        assertEquals("http://192.168.1.5:23342", server.baseUrl)
    }

    @Test
    fun `parseServiceInfo prefers non-loopback IPv4 over loopback IPv4`() {
        val serviceInfo = mockk<NsdServiceInfo>()
        val loopback = InetAddress.getByName("127.0.0.1")
        val lan = InetAddress.getByName("192.168.1.50")

        every { serviceInfo.serviceName } returns "kosh-server"
        every { serviceInfo.host } returns loopback
        every { serviceInfo.hostAddresses } returns listOf(loopback, lan)
        every { serviceInfo.port } returns 23342
        every { serviceInfo.serviceType } returns "_http._tcp."
        every { serviceInfo.attributes } returns emptyMap()

        val server = discoveryManager.parseServiceInfo(serviceInfo)
        assertNotNull(server)

        assertEquals("192.168.1.50", server!!.host)
    }

    @Test
    fun `parseServiceInfo gracefully handles NoSuchMethodError when hostAddresses is called on older platforms`() {
        val serviceInfo = mockk<NsdServiceInfo>()
        val ipv4Lan = InetAddress.getByName("192.168.1.10")

        every { serviceInfo.serviceName } returns "kosh-server"
        every { serviceInfo.host } returns ipv4Lan
        every { serviceInfo.hostAddresses } throws NoSuchMethodError("No getHostAddresses on API < 34")
        every { serviceInfo.port } returns 23342
        every { serviceInfo.serviceType } returns "_http._tcp."
        every { serviceInfo.attributes } returns emptyMap()

        val server = discoveryManager.parseServiceInfo(serviceInfo)
        assertNotNull(server)

        assertEquals("192.168.1.10", server!!.host)
        assertEquals("http://192.168.1.10:23342", server.baseUrl)
    }

    @Test
    fun `parseServiceInfo returns null when only link-local IPv6 is present without cache`() {
        val serviceInfo = mockk<NsdServiceInfo>()
        val ipv6LinkLocal = InetAddress.getByName("fe80::3d0c:a68f:5508:4ae3")

        every { serviceInfo.serviceName } returns "unknown-nonexistent-node"
        every { serviceInfo.host } returns ipv6LinkLocal
        every { serviceInfo.hostAddresses } returns listOf(ipv6LinkLocal)
        every { serviceInfo.port } returns 23342
        every { serviceInfo.serviceType } returns "_http._tcp."
        every { serviceInfo.attributes } returns emptyMap()

        val server = discoveryManager.parseServiceInfo(serviceInfo)

        // Must reject link-local IPv6 without cache and return null so caller awaits IPv4 update
        assertNull(server)
    }

    @Test
    fun `parseServiceInfo uses KoshDns cached IPv4 when only link-local IPv6 is present`() {
        KoshDns.setAddress("cached-node", "192.168.1.99")
        try {
            val serviceInfo = mockk<NsdServiceInfo>()
            val ipv6LinkLocal = InetAddress.getByName("fe80::3d0c:a68f:5508:4ae3")

            every { serviceInfo.serviceName } returns "cached-node"
            every { serviceInfo.host } returns ipv6LinkLocal
            every { serviceInfo.hostAddresses } returns listOf(ipv6LinkLocal)
            every { serviceInfo.port } returns 23342
            every { serviceInfo.serviceType } returns "_https._tcp."
            every { serviceInfo.attributes } returns emptyMap()

            val server = discoveryManager.parseServiceInfo(serviceInfo)

            assertNotNull(server)
            assertEquals("192.168.1.99", server!!.host)
        } finally {
            KoshDns.clear()
        }
    }
}
