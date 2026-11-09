package project.kosh.feature.auth

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import project.kosh.core.network.CertificateProbeResult
import project.kosh.core.network.CertificateProber
import project.kosh.core.network.DiscoveredServer
import project.kosh.core.network.MdnsDiscoveryManager
import project.kosh.core.network.ServerConfig
import project.kosh.core.network.ServerConfigurationStore

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSetupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val certificateProber: CertificateProber = mockk(relaxed = true)
    private val prefs: android.content.SharedPreferences = mockk(relaxed = true)
    private val serverConfigStore = ServerConfigurationStore(prefs)
    private val mdnsDiscoveryManager: MdnsDiscoveryManager = mockk(relaxed = true)

    private val sampleFingerprint = ByteArray(32) { it.toByte() }
    private val sampleFingerprintHex = sampleFingerprint.joinToString("") { "%02x".format(it) }
    private val sampleSasWords = listOf(
        "abandon", "ability", "able", "about",
        "above", "absent", "absorb", "abstract",
        "absurd", "abuse", "access", "accident"
    )

    private val sampleProbeResult = CertificateProbeResult(
        host = "kosh.local",
        port = 23342,
        isTls = true,
        fingerprintBytes = sampleFingerprint,
        fingerprintHex = sampleFingerprintHex,
        sasWords = sampleSasWords,
        subject = "CN=kosh",
        issuer = "CN=kosh"
    )

    private lateinit var viewModel: ServerSetupViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { mdnsDiscoveryManager.discoverServers() } returns flowOf(
            listOf(
                DiscoveredServer(
                    name = "kosh",
                    host = "192.168.1.5",
                    port = 23342,
                    isTls = true,
                    hostname = "kosh"
                )
            )
        )

        viewModel = ServerSetupViewModel(
            certificateProber = certificateProber,
            serverConfigStore = serverConfigStore,
            mdnsDiscoveryManager = mdnsDiscoveryManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Locating with no errors`() {
        val state = viewModel.uiState.value
        assertEquals(ServerSetupStage.Locating, state.stage)
        assertNull(state.probeError)
        assertFalse(state.showMismatchDialog)
        assertFalse(state.isVerified)
    }

    @Test
    fun `selectAndProbeServer with invalid parameters sets error`() {
        viewModel.selectAndProbeServer("", 23342, true)
        val state = viewModel.uiState.value
        assertEquals(ServerSetupStage.Locating, state.stage)
        assertNotNull(state.probeError)

        viewModel.selectAndProbeServer("127.0.0.1", -5, true)
        assertNotNull(viewModel.uiState.value.probeError)
    }

    @Test
    fun `selectAndProbeServer success transitions to Verification stage`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)

        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.stage is ServerSetupStage.Verification)
        val verification = state.stage as ServerSetupStage.Verification
        assertEquals("kosh.local", verification.result.host)
        assertEquals(sampleFingerprintHex, verification.result.fingerprintHex)
        assertNull(state.probeError)
    }

    @Test
    fun `selectAndProbeServer failure transitions back to Locating with diagnostic message`() = runTest {
        coEvery { certificateProber.probeServer("invalid.host", 23342, true) } returns Result.failure(
            java.net.ConnectException("Connection refused")
        )

        viewModel.selectAndProbeServer("invalid.host", 23342, true)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ServerSetupStage.Locating, state.stage)
        assertNotNull(state.probeError)
        assertTrue(state.probeError!!.contains("Connection refused"))
    }

    @Test
    fun `verifyScannedFingerprint with matching 32 raw bytes confirms and pins fingerprint`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        viewModel.verifyScannedFingerprint(rawBytes = sampleFingerprint, rawValue = null)

        val state = viewModel.uiState.value
        assertTrue(state.isVerified)
        assertFalse(state.showMismatchDialog)

        val savedConfig = serverConfigStore.getServerConfig()
        assertEquals("kosh.local", savedConfig.host)
        assertEquals(23342, savedConfig.port)
        assertTrue(savedConfig.useTls)
        assertTrue(sampleFingerprint.contentEquals(savedConfig.pinnedCertFingerprintSha256))
    }

    @Test
    fun `verifyScannedFingerprint with framed raw bytes matches and confirms`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        // 2 prefix bytes (e.g. QR mode/length) + 32 payload bytes + 2 trailer bytes
        val framedBytes = byteArrayOf(0x04, 0x20) + sampleFingerprint + byteArrayOf(0x00, 0xEC.toByte())
        viewModel.verifyScannedFingerprint(rawBytes = framedBytes, rawValue = null)

        val state = viewModel.uiState.value
        assertTrue(state.isVerified)
        assertFalse(state.showMismatchDialog)
    }

    @Test
    fun `verifyScannedFingerprint with 12-bit shifted 34-byte raw codewords matches and confirms`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        val rawCodewords = ByteArray(34)
        rawCodewords[0] = 0x42.toByte() // Mode 4 (Byte), high nibble of length 0x20 is 2
        rawCodewords[1] = ((0 and 0x0F) shl 4 or ((sampleFingerprint[0].toInt() and 0xF0) ushr 4)).toByte()
        for (i in 0 until 31) {
            val high = (sampleFingerprint[i].toInt() and 0x0F) shl 4
            val low = (sampleFingerprint[i + 1].toInt() and 0xF0) ushr 4
            rawCodewords[2 + i] = (high or low).toByte()
        }
        rawCodewords[33] = ((sampleFingerprint[31].toInt() and 0x0F) shl 4).toByte()

        viewModel.verifyScannedFingerprint(rawBytes = rawCodewords, rawValue = null)

        val state = viewModel.uiState.value
        assertTrue(state.isVerified)
        assertFalse(state.showMismatchDialog)
    }

    @Test
    fun `verifyScannedFingerprint with ISO-8859-1 string matches and confirms`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        val isoString = String(sampleFingerprint, Charsets.ISO_8859_1)
        viewModel.verifyScannedFingerprint(rawBytes = null, rawValue = isoString)

        val state = viewModel.uiState.value
        assertTrue(state.isVerified)
        assertFalse(state.showMismatchDialog)
    }

    @Test
    fun `verifyScannedFingerprint with matching hex string confirms and pins fingerprint`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        viewModel.verifyScannedFingerprint(rawBytes = null, rawValue = sampleFingerprintHex)

        val state = viewModel.uiState.value
        assertTrue(state.isVerified)
        assertFalse(state.showMismatchDialog)
    }

    @Test
    fun `verifyScannedFingerprint with matching SAS words confirms and pins fingerprint`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        viewModel.verifyScannedFingerprint(rawBytes = null, rawValue = sampleSasWords.joinToString(" "))

        val state = viewModel.uiState.value
        assertTrue(state.isVerified)
        assertFalse(state.showMismatchDialog)
    }

    @Test
    fun `verifyScannedFingerprint with mismatching code triggers mismatch dialog`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        val wrongFingerprint = ByteArray(32) { 0xFF.toByte() }
        viewModel.verifyScannedFingerprint(rawBytes = wrongFingerprint, rawValue = "bad_hex_code")

        val state = viewModel.uiState.value
        assertFalse(state.isVerified)
        assertTrue(state.showMismatchDialog)
        assertEquals("bad_hex_code", state.mismatchScannedText)
    }

    @Test
    fun `backToLocating resets stage to Locating`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        viewModel.backToLocating()

        assertEquals(ServerSetupStage.Locating, viewModel.uiState.value.stage)
        assertFalse(viewModel.uiState.value.showMismatchDialog)
    }

    @Test
    fun `dismissMismatchDialog hides dialog but preserves stage`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        viewModel.verifyScannedFingerprint(rawBytes = ByteArray(32), rawValue = "mismatch")
        assertTrue(viewModel.uiState.value.showMismatchDialog)

        viewModel.dismissMismatchDialog()
        assertFalse(viewModel.uiState.value.showMismatchDialog)
        assertTrue(viewModel.uiState.value.stage is ServerSetupStage.Verification)
    }

    @Test
    fun `verifyScannedFingerprint with mixed-case hex matches and confirms`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        val mixedHex = sampleFingerprintHex.mapIndexed { i, c -> if (i % 2 == 0) c.uppercaseChar() else c }.joinToString("")
        viewModel.verifyScannedFingerprint(rawBytes = null, rawValue = mixedHex)

        val state = viewModel.uiState.value
        assertTrue(state.isVerified)
        assertFalse(state.showMismatchDialog)
    }

    @Test
    fun `verifyScannedFingerprint with sha256 base64 format matches and confirms`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        val b64 = java.util.Base64.getEncoder().encodeToString(sampleFingerprint)
        viewModel.verifyScannedFingerprint(rawBytes = null, rawValue = "sha256/$b64")

        val state = viewModel.uiState.value
        assertTrue(state.isVerified)
        assertFalse(state.showMismatchDialog)
    }

    @Test
    fun `verifyScannedFingerprint with short raw bytes triggers mismatch dialog`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        val shortBytes = ByteArray(16) { 0x01 }
        viewModel.verifyScannedFingerprint(rawBytes = shortBytes, rawValue = null)

        val state = viewModel.uiState.value
        assertFalse(state.isVerified)
        assertTrue(state.showMismatchDialog)
        assertEquals(ServerConfigurationStore.bytesToHex(shortBytes), state.mismatchScannedText)
    }

    @Test
    fun `verifyScannedFingerprint with null inputs triggers mismatch dialog`() = runTest {
        coEvery { certificateProber.probeServer("kosh.local", 23342, true) } returns Result.success(sampleProbeResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, true)
        advanceUntilIdle()

        viewModel.verifyScannedFingerprint(rawBytes = null, rawValue = null)

        val state = viewModel.uiState.value
        assertFalse(state.isVerified)
        assertTrue(state.showMismatchDialog)
        assertEquals("Unknown code format", state.mismatchScannedText)
    }

    @Test
    fun `verifyScannedFingerprint when unencrypted HTTP returns early without state change`() = runTest {
        val plaintextResult = CertificateProbeResult(
            host = "kosh.local",
            port = 23342,
            isTls = false,
            fingerprintBytes = ByteArray(0),
            fingerprintHex = "",
            sasWords = emptyList(),
            subject = "Plaintext HTTP",
            issuer = "None"
        )
        coEvery { certificateProber.probeServer("kosh.local", 23342, false) } returns Result.success(plaintextResult)
        viewModel.selectAndProbeServer("kosh.local", 23342, false)
        advanceUntilIdle()

        viewModel.verifyScannedFingerprint(rawBytes = sampleFingerprint, rawValue = null)

        val state = viewModel.uiState.value
        assertFalse(state.isVerified)
        assertFalse(state.showMismatchDialog)
    }

    @Test
    fun `connectAdb probes localhost with autoProbe and transitions to Verification`() = runTest {
        val adbProbeResult = sampleProbeResult.copy(host = "127.0.0.1")
        coEvery { certificateProber.autoProbe("127.0.0.1", 23342) } returns Result.success(adbProbeResult)

        viewModel.connectAdb()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.stage is ServerSetupStage.Verification)
        assertEquals("127.0.0.1", (state.stage as ServerSetupStage.Verification).result.host)
        assertNull(state.probeError)
    }

    @Test
    fun `connectAdb failure sets informative adb error message`() = runTest {
        coEvery {
            certificateProber.autoProbe("127.0.0.1", 23342)
        } returns Result.failure(java.net.ConnectException("Connection refused"))

        viewModel.connectAdb()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(ServerSetupStage.Locating, state.stage)
        assertTrue(state.probeError!!.contains("adb reverse tcp:23342 tcp:23342"))
    }

    @Test
    fun `connectEmulator probes emulator loopback with autoProbe and transitions to Verification`() = runTest {
        val emulatorProbeResult = sampleProbeResult.copy(host = "10.0.2.2")
        coEvery { certificateProber.autoProbe("10.0.2.2", 23342) } returns Result.success(emulatorProbeResult)

        viewModel.connectEmulator()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.stage is ServerSetupStage.Verification)
        assertEquals("10.0.2.2", (state.stage as ServerSetupStage.Verification).result.host)
    }
}
