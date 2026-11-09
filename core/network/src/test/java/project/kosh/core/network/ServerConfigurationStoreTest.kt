package project.kosh.core.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerConfigurationStoreTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var store: ServerConfigurationStore

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        store = ServerConfigurationStore(fakePrefs)
    }

    @Test
    fun `default configuration uses AVD loopback and canonical port 23342 with HTTP`() {
        val config = store.getServerConfig()

        assertEquals(ServerConfig.DEFAULT_HOST, config.host)
        assertEquals(ServerConfig.DEFAULT_PORT, config.port)
        assertFalse(config.useTls)
        assertNull(config.pinnedCertFingerprintSha256)
        assertEquals("http://10.0.2.2:23342", config.baseUrl)
        assertFalse(store.hasConfiguredHost())
    }

    @Test
    fun `updateServer persists connection parameters and emits reactive state`() = runTest {
        store.updateServer(
            host = "192.168.1.150",
            port = 23342,
            useTls = false
        )

        val updated = store.getServerConfig()
        assertEquals("192.168.1.150", updated.host)
        assertEquals(23342, updated.port)
        assertFalse(updated.useTls)
        assertEquals("http://192.168.1.150:23342", updated.baseUrl)
        assertTrue(store.hasConfiguredHost())

        val flowValue = store.serverConfig.first()
        assertEquals(updated, flowValue)
    }

    @Test
    fun `pinned fingerprint persists accurately and formats hex and base64 correctly`() {
        val fingerprint = ByteArray(32) { (it + 1).toByte() }

        store.updatePinnedFingerprint(fingerprint)

        val config = store.getServerConfig()
        assertNotNull(config.pinnedCertFingerprintSha256)
        assertArrayEquals(fingerprint, config.pinnedCertFingerprintSha256)

        // 0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20
        val hex = config.fingerprintHex
        assertNotNull(hex)
        assertEquals(64, hex!!.length)
        assertTrue(hex.startsWith("0102030405"))

        val b64 = config.fingerprintBase64
        assertNotNull(b64)
        assertTrue(b64!!.startsWith("sha256/"))
    }

    @Test
    fun `clear resets configuration to defaults and removes persistent keys`() {
        store.updateServer(
            host = "kosh-nas.local",
            port = 8443,
            useTls = true,
            pinnedCertFingerprintSha256 = ByteArray(32) { 0xff.toByte() }
        )
        assertTrue(store.hasConfiguredHost())

        store.clear()

        val reset = store.getServerConfig()
        assertEquals(ServerConfig.DEFAULT_HOST, reset.host)
        assertEquals(ServerConfig.DEFAULT_PORT, reset.port)
        assertFalse(reset.useTls)
        assertNull(reset.pinnedCertFingerprintSha256)
        assertFalse(store.hasConfiguredHost())
    }

    @Test
    fun `baseUrl formats IPv6 addresses with RFC 3986 square brackets`() {
        store.updateServer(
            host = "::1",
            port = 6969,
            useTls = true
        )
        assertEquals("https://[::1]:6969", store.getBaseUrl())

        store.updateServer(
            host = "[2001:db8::1]",
            port = 8443,
            useTls = false
        )
        assertEquals("http://[2001:db8::1]:8443", store.getBaseUrl())
    }

    @Test
    fun `parseFingerprint parses hex, sha256 prefix, and raw base64`() {
        val originalBytes = ByteArray(32) { (it + 0x10).toByte() }
        val hex = ServerConfigurationStore.bytesToHex(originalBytes)
        val b64 = java.util.Base64.getEncoder().encodeToString(originalBytes)

        // Lowercase hex
        assertArrayEquals(originalBytes, ServerConfigurationStore.parseFingerprint(hex))
        // Uppercase hex
        assertArrayEquals(originalBytes, ServerConfigurationStore.parseFingerprint(hex.uppercase()))
        // sha256/<base64>
        assertArrayEquals(originalBytes, ServerConfigurationStore.parseFingerprint("sha256/$b64"))
        // raw base64
        assertArrayEquals(originalBytes, ServerConfigurationStore.parseFingerprint(b64))

        // Invalid inputs
        assertNull(ServerConfigurationStore.parseFingerprint(""))
        assertNull(ServerConfigurationStore.parseFingerprint("invalid-hex-zz"))
        assertNull(ServerConfigurationStore.parseFingerprint("010203")) // too short hex
        assertNull(ServerConfigurationStore.parseFingerprint("sha256/notvalidbase64!"))
    }

    @Test
    fun `updatePinnedFingerprint with string successfully parses and persists`() {
        val originalBytes = ByteArray(32) { 0x5a.toByte() }
        val hex = ServerConfigurationStore.bytesToHex(originalBytes)

        val updated = store.updatePinnedFingerprint(hex)
        assertTrue(updated)
        assertArrayEquals(originalBytes, store.getPinnedFingerprint())

        val unpinned = store.updatePinnedFingerprint(null as String?)
        assertTrue(unpinned)
        assertNull(store.getPinnedFingerprint())

        val invalid = store.updatePinnedFingerprint("bad-input")
        assertFalse(invalid)
    }

    @Test
    fun `ServerConfig enforces port range and fingerprint length`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ServerConfig(port = 0)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ServerConfig(port = 70000)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ServerConfig(pinnedCertFingerprintSha256 = ByteArray(16))
        }
    }

    @Test
    fun `hexToBytes safely rejects odd-length and non-hex inputs`() {
        assertNull(ServerConfigurationStore.hexToBytes("123"))
        assertNull(ServerConfigurationStore.hexToBytes("123g"))
        assertNotNull(ServerConfigurationStore.hexToBytes("1234"))
    }

    @Test
    fun `loadConfig and hasConfiguredHost treat fe80 link-local address as unconfigured`() {
        fakePrefs.edit()
            .putString(ServerConfigurationStore.KEY_HOST, "fe80::3d0c:a68f:5508:4ae3")
            .putInt(ServerConfigurationStore.KEY_PORT, 23342)
            .apply()

        val newStore = ServerConfigurationStore(fakePrefs)
        val config = newStore.getServerConfig()

        assertEquals(ServerConfig.DEFAULT_HOST, config.host)
        assertFalse(newStore.hasConfiguredHost())
    }
}
