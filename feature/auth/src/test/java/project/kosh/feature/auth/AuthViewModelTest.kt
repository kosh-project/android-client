package project.kosh.feature.auth

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import project.kosh.core.auth.AuthRepository
import project.kosh.core.auth.PasskeyAuthManager
import project.kosh.core.auth.PasskeyLoginResult
import project.kosh.core.auth.PasskeyRegisterResult
import project.kosh.core.crypto.IdentityManager
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.MdnsDiscoveryManager
import project.kosh.core.network.ServerConfig
import project.kosh.core.network.ServerConfigurationStore
import project.kosh.core.network.ServerHealthPinger

import project.kosh.core.auth.DevicePairingManager
import project.kosh.core.auth.PairingState

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockRepository: AuthRepository
    private lateinit var mockIdentityManager: IdentityManager
    private lateinit var mockPasskeyManager: PasskeyAuthManager
    private lateinit var mockServerConfigStore: ServerConfigurationStore
    private lateinit var mockMdnsManager: MdnsDiscoveryManager
    private lateinit var mockHealthPinger: ServerHealthPinger
    private lateinit var mockDevicePairingManager: DevicePairingManager

    private lateinit var viewModel: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockRepository = mockk(relaxed = true)
        mockIdentityManager = mockk(relaxed = true)
        mockPasskeyManager = mockk(relaxed = true)
        mockServerConfigStore = mockk(relaxed = true)
        mockMdnsManager = mockk(relaxed = true)
        mockHealthPinger = mockk(relaxed = true)
        mockDevicePairingManager = mockk(relaxed = true)

        every { mockMdnsManager.discoverServers() } returns flowOf(emptyList())

        viewModel = AuthViewModel(
            authRepository = mockRepository,
            identityManager = mockIdentityManager,
            passkeyAuthManager = mockPasskeyManager,
            serverConfigStore = mockServerConfigStore,
            mdnsDiscoveryManager = mockMdnsManager,
            serverHealthPinger = mockHealthPinger,
            devicePairingManager = mockDevicePairingManager
        ).apply {
            defaultDispatcher = testDispatcher
        }
    }

    @Test
    fun `when network fails, state becomes error`() = runTest {
        assertTrue(viewModel.state.value is AuthUiState.Idle)

        coEvery {
            mockRepository.login("user", "pass")
        } returns NetworkResult.fromHttp(401, "Unauthorized")

        viewModel.login("user", "pass")

        testScheduler.advanceUntilIdle()

        val finalState = viewModel.state.value
        assertTrue("State should be Error, but was $finalState", finalState is AuthUiState.Error)
        assertEquals("Unauthorized", (finalState as AuthUiState.Error).message)
    }

    @Test
    fun `when login succeeds, state becomes Success with isNewRegistration false`() = runTest {
        coEvery {
            mockRepository.login("alice", "correct-horse-battery")
        } returns NetworkResult.Success("test-bearer-token")

        viewModel.login("alice", "correct-horse-battery")

        testScheduler.advanceUntilIdle()

        val finalState = viewModel.state.value
        assertTrue(finalState is AuthUiState.Success)
        assertFalse((finalState as AuthUiState.Success).isNewRegistration)
    }

    @Test
    fun `when login inputs are blank, returns immediate error without network call`() = runTest {
        viewModel.login("   ", "")

        val finalState = viewModel.state.value
        assertTrue(finalState is AuthUiState.Error)
        assertEquals("Username and passphrase cannot be blank.", (finalState as AuthUiState.Error).message)
    }

    @Test
    fun `when register succeeds, state becomes Success with isNewRegistration true`() = runTest {
        coEvery {
            mockRepository.register("bob_vault", "strongpassphrase123")
        } returns NetworkResult.Success(Unit)
        coEvery {
            mockRepository.login("bob_vault", "strongpassphrase123")
        } returns NetworkResult.Success("test-session-token")

        viewModel.register("bob_vault", "strongpassphrase123")

        testScheduler.advanceUntilIdle()

        val finalState = viewModel.state.value
        assertTrue(finalState is AuthUiState.Success)
        assertTrue((finalState as AuthUiState.Success).isNewRegistration)
    }

    @Test
    fun `when register succeeds but post-registration login fails, state becomes Error`() = runTest {
        coEvery {
            mockRepository.register("carol_vault", "strongpassphrase123")
        } returns NetworkResult.Success(Unit)
        coEvery {
            mockRepository.login("carol_vault", "strongpassphrase123")
        } returns NetworkResult.fromHttp(500, "Internal Server Error")

        viewModel.register("carol_vault", "strongpassphrase123")

        testScheduler.advanceUntilIdle()

        val finalState = viewModel.state.value
        assertTrue(finalState is AuthUiState.Error)
        assertEquals("Internal Server Error", (finalState as AuthUiState.Error).message)
    }

    @Test
    fun `when register passphrase is too short, returns immediate error`() = runTest {
        viewModel.register("validuser", "short")

        val finalState = viewModel.state.value
        assertTrue(finalState is AuthUiState.Error)
        assertEquals("Passphrase must be at least 8 characters.", (finalState as AuthUiState.Error).message)
    }

    @Test
    fun `when passkey login succeeds with master key, state becomes Success`() = runTest {
        val mockContext: Context = mockk()
        val dummyMasterKey = MasterVaultKey(ByteArray(32) { 7 })

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext, any())
        } returns PasskeyLoginResult.Success(
            token = "passkey-token",
            wrappedVaultKey = "aabb",
            masterVaultKey = dummyMasterKey
        )

        viewModel.loginWithPasskey(mockContext)

        testScheduler.advanceUntilIdle()

        val finalState = viewModel.state.value
        assertTrue(finalState is AuthUiState.Success)
        assertFalse((finalState as AuthUiState.Success).isNewRegistration)
    }

    @Test
    fun `when passkey login succeeds without cached key, state becomes VaultUnlockRequired`() = runTest {
        val mockContext: Context = mockk()
        every { mockRepository.isLoggedIn() } returns false

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext, any())
        } returns PasskeyLoginResult.Success(
            token = "passkey-token",
            wrappedVaultKey = "envelope-hex-120-chars",
            masterVaultKey = null
        )

        viewModel.loginWithPasskey(mockContext)

        testScheduler.advanceUntilIdle()

        val finalState = viewModel.state.value
        assertTrue(finalState is AuthUiState.VaultUnlockRequired)
        val unlockState = finalState as AuthUiState.VaultUnlockRequired
        assertEquals("passkey-token", unlockState.token)
        assertEquals("envelope-hex-120-chars", unlockState.wrappedVaultKey)
    }

    @Test
    fun `when unlockVault succeeds, state becomes Success`() = runTest {
        val mockContext: Context = mockk()
        every { mockRepository.isLoggedIn() } returns false

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext, any())
        } returns PasskeyLoginResult.Success(
            token = "passkey-token",
            wrappedVaultKey = "aabb",
            masterVaultKey = null
        )

        viewModel.loginWithPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        val dummyKey = MasterVaultKey(ByteArray(32) { 1 })
        every {
            mockPasskeyManager.unlockVaultWithPassphrase("aabb", "correctpass")
        } returns dummyKey

        viewModel.unlockVault("aabb", "correctpass")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is AuthUiState.Success)
        assertFalse((state as AuthUiState.Success).isNewRegistration)
    }

    @Test
    fun `when unlockVault with blank passphrase, retains error without unwrap`() = runTest {
        val mockContext: Context = mockk()
        every { mockRepository.isLoggedIn() } returns false

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext, any())
        } returns PasskeyLoginResult.Success(
            token = "passkey-token",
            wrappedVaultKey = "aabb",
            masterVaultKey = null
        )

        viewModel.loginWithPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        viewModel.unlockVault("aabb", "   ")

        val state = viewModel.state.value
        assertTrue(state is AuthUiState.VaultUnlockRequired)
        val unlockState = state as AuthUiState.VaultUnlockRequired
        assertEquals("Passphrase cannot be blank.", unlockState.errorMessage)
        verify(exactly = 0) { mockPasskeyManager.unlockVaultWithPassphrase(any(), any()) }
    }

    @Test
    fun `when unlockVault fails, error is retained within VaultUnlockRequired`() = runTest {
        val mockContext: Context = mockk()
        every { mockRepository.isLoggedIn() } returns false

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext, any())
        } returns PasskeyLoginResult.Success(
            token = "passkey-token",
            wrappedVaultKey = "aabb",
            masterVaultKey = null
        )

        viewModel.loginWithPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        every {
            mockPasskeyManager.unlockVaultWithPassphrase("aabb", "wrongpass")
        } returns null

        viewModel.unlockVault("aabb", "wrongpass")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is AuthUiState.VaultUnlockRequired)
        val unlockState = state as AuthUiState.VaultUnlockRequired
        assertEquals("Invalid passphrase. Could not unwrap vault key.", unlockState.errorMessage)
    }

    @Test
    fun `when unlockVault throws unexpected exception, error is retained within VaultUnlockRequired`() = runTest {
        val mockContext: Context = mockk()
        every { mockRepository.isLoggedIn() } returns false

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext, any())
        } returns PasskeyLoginResult.Success(
            token = "passkey-token",
            wrappedVaultKey = "aabb",
            masterVaultKey = null
        )

        viewModel.loginWithPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        every {
            mockPasskeyManager.unlockVaultWithPassphrase("aabb", "errorpass")
        } throws IllegalStateException("Crypto driver unavailable")

        viewModel.unlockVault("aabb", "errorpass")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is AuthUiState.VaultUnlockRequired)
        val unlockState = state as AuthUiState.VaultUnlockRequired
        assertEquals("Vault unlock error: Crypto driver unavailable", unlockState.errorMessage)
    }

    @Test
    fun `when cancelVaultUnlock is called, logs out and resets to Idle`() = runTest {
        val mockContext: Context = mockk()
        every { mockRepository.isLoggedIn() } returns false

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext, any())
        } returns PasskeyLoginResult.Success(
            token = "passkey-token",
            wrappedVaultKey = "aabb",
            masterVaultKey = null
        )

        viewModel.loginWithPasskey(mockContext)
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value is AuthUiState.VaultUnlockRequired)

        viewModel.cancelVaultUnlock()

        verify { mockRepository.logout() }
        assertEquals(AuthUiState.Idle, viewModel.state.value)
    }

    @Test
    fun `when passkey login is cancelled, state returns to Idle`() = runTest {
        val mockContext: Context = mockk()
        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext, any())
        } returns PasskeyLoginResult.Cancelled

        viewModel.loginWithPasskey(mockContext)

        testScheduler.advanceUntilIdle()

        val finalState = viewModel.state.value
        assertTrue(finalState is AuthUiState.Idle)
    }

    @Test
    fun `when registerWithPasskey succeeds, state becomes Success with 24 recovery words`() = runTest {
        val mockContext: Context = mockk()
        coEvery { mockRepository.registerWithRecoveryPhrase(any()) } returns NetworkResult.Success(Unit)
        coEvery { mockRepository.loginWithRecoveryPhrase(any()) } returns NetworkResult.Success("session-token")
        coEvery { mockPasskeyManager.registerPasskey(mockContext, any()) } returns PasskeyRegisterResult.Success

        viewModel.registerWithPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected Success state, but was $state", state is AuthUiState.Success)
        val success = state as AuthUiState.Success
        assertTrue(success.isNewRegistration)
        assertNotNull(success.recoveryWords)
        assertEquals(24, success.recoveryWords!!.size)
    }

    @Test
    fun `when registerWithPasskey fails at server registration, state becomes Error`() = runTest {
        val mockContext: Context = mockk()
        coEvery {
            mockRepository.registerWithRecoveryPhrase(any())
        } returns NetworkResult.fromHttp(409, "Identity conflict")

        viewModel.registerWithPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected Error state, but was $state", state is AuthUiState.Error)
        assertEquals("Identity conflict", (state as AuthUiState.Error).message)
    }

    @Test
    fun `when registerWithPasskey encounters passkey error, registration still succeeds with recovery words and no logout`() = runTest {
        val mockContext: Context = mockk()
        coEvery { mockRepository.registerWithRecoveryPhrase(any()) } returns NetworkResult.Success(Unit)
        coEvery { mockRepository.loginWithRecoveryPhrase(any()) } returns NetworkResult.Success("session-token")
        coEvery {
            mockPasskeyManager.registerPasskey(mockContext, any())
        } returns PasskeyRegisterResult.Error("Hardware keystore error")

        viewModel.registerWithPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected Success state, but was $state", state is AuthUiState.Success)
        val success = state as AuthUiState.Success
        assertTrue(success.isNewRegistration)
        assertNotNull(success.recoveryWords)
        assertEquals(24, success.recoveryWords!!.size)
        verify(exactly = 0) { mockRepository.logout() }
    }

    @Test
    fun `when registerWithPasskey is cancelled by user, registration still succeeds with recovery words and no logout`() = runTest {
        val mockContext: Context = mockk()
        coEvery { mockRepository.registerWithRecoveryPhrase(any()) } returns NetworkResult.Success(Unit)
        coEvery { mockRepository.loginWithRecoveryPhrase(any()) } returns NetworkResult.Success("session-token")
        coEvery {
            mockPasskeyManager.registerPasskey(mockContext, any())
        } returns PasskeyRegisterResult.Cancelled

        viewModel.registerWithPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected Success state, but was $state", state is AuthUiState.Success)
        val success = state as AuthUiState.Success
        assertTrue(success.isNewRegistration)
        assertNotNull(success.recoveryWords)
        assertEquals(24, success.recoveryWords!!.size)
        verify(exactly = 0) { mockRepository.logout() }
    }

    @Test
    fun `when recoverWithPassphrase succeeds, state becomes Success with isNewRegistration false`() = runTest {
        coEvery {
            mockRepository.loginWithRecoveryPhrase("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        } returns NetworkResult.Success("recovered-session-token")

        viewModel.recoverWithPassphrase("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected Success state, but was $state", state is AuthUiState.Success)
        assertFalse((state as AuthUiState.Success).isNewRegistration)
    }

    @Test
    fun `when recoverWithPassphrase succeeds with 24-word phrase, state becomes Success`() = runTest {
        val phrase24 = (List(23) { "abandon" } + listOf("art")).joinToString(" ")
        coEvery {
            mockRepository.loginWithRecoveryPhrase(phrase24)
        } returns NetworkResult.Success("recovered-session-token-24")

        viewModel.recoverWithPassphrase(phrase24)
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue("Expected Success state, but was $state", state is AuthUiState.Success)
        assertFalse((state as AuthUiState.Success).isNewRegistration)
    }

    @Test
    fun `when recoverWithPassphrase fails, state becomes Error`() = runTest {
        coEvery {
            mockRepository.loginWithRecoveryPhrase("wrong phrase")
        } returns NetworkResult.fromHttp(401, "Invalid credentials")

        viewModel.recoverWithPassphrase("wrong phrase")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is AuthUiState.Error)
        assertEquals("Invalid credentials", (state as AuthUiState.Error).message)
    }

    @Test
    fun `when recoverWithPassphrase is blank, immediate Error without network call`() = runTest {
        viewModel.recoverWithPassphrase("   ")

        val state = viewModel.state.value
        assertTrue(state is AuthUiState.Error)
        assertEquals("Recovery passphrase cannot be blank.", (state as AuthUiState.Error).message)
    }

    @Test
    fun `updateServerConfig delegates to serverConfigStore`() {
        viewModel.updateServerConfig("192.168.1.100", 23342, false)

        verify {
            mockServerConfigStore.updateServer("192.168.1.100", 23342, false)
        }
    }

    @Test
    fun `init does not auto-adopt discovered server without user verification`() = runTest {
        val server = project.kosh.core.network.DiscoveredServer(
            name = "kosh",
            host = "192.168.1.5",
            port = 23342,
            isTls = false
        )
        every { mockServerConfigStore.hasConfiguredHost() } returns false
        every { mockMdnsManager.discoverServers() } returns flowOf(listOf(server))

        val vm = AuthViewModel(
            authRepository = mockRepository,
            identityManager = mockIdentityManager,
            passkeyAuthManager = mockPasskeyManager,
            serverConfigStore = mockServerConfigStore,
            mdnsDiscoveryManager = mockMdnsManager,
            serverHealthPinger = mockHealthPinger,
            devicePairingManager = mockDevicePairingManager
        )
        testScheduler.advanceUntilIdle()

        verify(exactly = 0) {
            mockServerConfigStore.updateServer(any(), any(), any(), any())
        }
    }

    @Test
    fun `when ping succeeds, pingState becomes Success`() = runTest {
        coEvery {
            mockHealthPinger.ping("192.168.1.5", 23342, true)
        } returns project.kosh.core.network.PingResult.Success(
            latencyMs = 42L,
            statusCode = 200,
            isTls = true
        )

        viewModel.pingServer("192.168.1.5", 23342, true)
        testScheduler.advanceUntilIdle()

        val state = viewModel.pingState.value
        assertTrue(state is PingUiState.Success)
        val success = state as PingUiState.Success
        assertEquals(42L, success.latencyMs)
        assertEquals(200, success.statusCode)
        assertTrue(success.isTls)
    }

    @Test
    fun `when ping fails, pingState becomes Error`() = runTest {
        coEvery {
            mockHealthPinger.ping("10.0.2.2", 23342, false)
        } returns project.kosh.core.network.PingResult.Failure(
            error = "Connection refused",
            isTls = false
        )

        viewModel.pingServer("10.0.2.2", 23342, false)
        testScheduler.advanceUntilIdle()

        val state = viewModel.pingState.value
        assertTrue(state is PingUiState.Error)
        val error = state as PingUiState.Error
        assertEquals("Connection refused", error.message)
        assertFalse(error.isTls)
    }

    @Test
    fun `when ping invalid port, pingState becomes Error immediately`() = runTest {
        viewModel.pingServer("127.0.0.1", 999999, false)

        val state = viewModel.pingState.value
        assertTrue(state is PingUiState.Error)
    }

    @Test
    fun `hasStoredCredentials reflects presence of enrolled passkeys or session keys`() {
        every { mockPasskeyManager.hasEnrolledPasskeys() } returns true
        every { mockRepository.hasStoredCredentials() } returns false
        viewModel.refreshStoredCredentials()
        assertTrue(viewModel.hasStoredCredentials.value)

        every { mockPasskeyManager.hasEnrolledPasskeys() } returns false
        every { mockRepository.hasStoredCredentials() } returns false
        viewModel.refreshStoredCredentials()
        assertFalse(viewModel.hasStoredCredentials.value)
    }

    @Test
    fun `startDevicePairing collects receiver flow and transitions to success upon fulfillment`() = runTest {
        val masterKey = MasterVaultKey.generate()
        every { mockRepository.hasVaultKey() } returns false
        every { mockPasskeyManager.hasEnrolledPasskeys() } returns true
        viewModel.refreshStoredCredentials()

        coEvery { mockDevicePairingManager.startReceiverFlow(any()) } returns flowOf(
            PairingState.Ready("c7m9x4b2", "{}", 300L),
            PairingState.AwaitingFulfillment,
            PairingState.Success(masterKey, "ticket-aabbcc")
        )

        viewModel.startDevicePairing()
        testScheduler.advanceUntilIdle()

        assertEquals(PairingState.Success(masterKey, "ticket-aabbcc"), viewModel.pairingState.value)
        assertEquals("ticket-aabbcc", viewModel.lastRegistrationTicket.value)
        assertTrue(viewModel.state.value is AuthUiState.Success)
        assertTrue((viewModel.state.value as AuthUiState.Success).isNewRegistration)
    }

    @Test
    fun `startDevicePairing initiates host flow when device has vault key`() = runTest {
        every { mockRepository.hasVaultKey() } returns true
        coEvery { mockDevicePairingManager.startHostFlow(any(), any()) } returns flowOf(
            PairingState.AwaitingJoin("c7m9x4b2", "{\"mailbox_id\":\"c7m9x4b2\"}", 300L)
        )

        viewModel.startDevicePairing()
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.pairingState.value is PairingState.AwaitingJoin)
        assertEquals("c7m9x4b2", (viewModel.pairingState.value as PairingState.AwaitingJoin).mailboxId)
    }

    @Test
    fun `linkWithScannedQr fulfills sender session when device already has vault key`() = runTest {
        every { mockRepository.hasVaultKey() } returns true
        coEvery {
            mockDevicePairingManager.fulfillSenderSession(
                mailboxId = "c7m9x4b2",
                receiverPublicKeyHex = "aabbcc",
                token = "peer-token"
            )
        } returns NetworkResult.Success(Unit)

        val qrJson = """{"mailbox_id":"c7m9x4b2","public_key":"aabbcc","token":"peer-token"}"""
        viewModel.linkWithScannedQr(qrJson)
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value is AuthUiState.Success)
    }

    @Test
    fun `linkWithScannedQr joins host session when device has no vault key`() = runTest {
        val masterKey = MasterVaultKey.generate()
        every { mockRepository.hasVaultKey() } returns false
        coEvery {
            mockDevicePairingManager.startJoinFlow(
                mailboxId = "c7m9x4b2",
                host = "192.168.1.5",
                port = 23342,
                fingerprint = any()
            )
        } returns flowOf(
            PairingState.AwaitingFulfillment,
            PairingState.Success(masterKey, "ticket-xyz")
        )

        val qrJson = """{"mailbox_id":"c7m9x4b2","host":"192.168.1.5","port":23342,"mode":"host"}"""
        viewModel.linkWithScannedQr(qrJson)
        testScheduler.advanceUntilIdle()

        assertEquals("ticket-xyz", viewModel.lastRegistrationTicket.value)
        assertTrue(viewModel.state.value is AuthUiState.Success)
        assertTrue((viewModel.state.value as AuthUiState.Success).isNewRegistration)
    }

    @Test
    fun `enrollPasskeyWithTicket delegates to passkeyAuthManager and transitions to success`() = runTest {
        val mockContext: Context = mockk()
        coEvery {
            mockPasskeyManager.registerPasskeyWithTicket(
                activityContext = mockContext,
                registrationTicket = "ticket-secret",
                deviceNickname = "Pixel 9"
            )
        } returns PasskeyRegisterResult.Success

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext)
        } returns PasskeyLoginResult.Success("session-token", "dummy-wrapped-key", null)

        viewModel.enrollPasskeyWithTicket(mockContext, "ticket-secret", "Pixel 9")
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value is AuthUiState.Success)
    }

    @Test
    fun `enrollPasskeyWithTicket transitions to error when loginWithPasskey returns Error`() = runTest {
        val mockContext: Context = mockk()
        coEvery {
            mockPasskeyManager.registerPasskeyWithTicket(
                activityContext = mockContext,
                registrationTicket = "ticket-secret",
                deviceNickname = "Pixel 9"
            )
        } returns PasskeyRegisterResult.Success

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext)
        } returns PasskeyLoginResult.Error("Biometric sensor timeout")

        viewModel.enrollPasskeyWithTicket(mockContext, "ticket-secret", "Pixel 9")
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value is AuthUiState.Error)
        assertEquals("Passkey login failed: Biometric sensor timeout", (viewModel.state.value as AuthUiState.Error).message)
    }

    @Test
    fun `enrollPasskeyWithTicket transitions to error when loginWithPasskey is cancelled`() = runTest {
        val mockContext: Context = mockk()
        coEvery {
            mockPasskeyManager.registerPasskeyWithTicket(
                activityContext = mockContext,
                registrationTicket = "ticket-secret",
                deviceNickname = "Pixel 9"
            )
        } returns PasskeyRegisterResult.Success

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext)
        } returns PasskeyLoginResult.Cancelled

        viewModel.enrollPasskeyWithTicket(mockContext, "ticket-secret", "Pixel 9")
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value is AuthUiState.Error)
        assertEquals("Passkey login cancelled", (viewModel.state.value as AuthUiState.Error).message)
    }

    @Test
    fun `linkWithScannedQr forwards certificate fingerprint to ServerConfigurationStore`() = runTest {
        val masterKey = MasterVaultKey.generate()
        every { mockRepository.hasVaultKey() } returns true
        coEvery {
            mockDevicePairingManager.fulfillSenderSession(any(), any(), any(), any())
        } returns NetworkResult.Success(Unit)

        val testFingerprintHex = "00".repeat(31) + "ff"
        val testPubKeyHex = "aa".repeat(32)
        val qrJson = """{"mailbox_id":"c7m9x4b2","public_key":"$testPubKeyHex","host":"192.168.1.50","port":8443,"fingerprint":"$testFingerprintHex"}"""
        viewModel.linkWithScannedQr(qrJson)
        testScheduler.advanceUntilIdle()

        io.mockk.verify(exactly = 1) {
            mockServerConfigStore.updateServer(
                host = "192.168.1.50",
                port = 8443,
                useTls = true,
                pinnedCertFingerprintSha256 = match { it.size == 32 && it[31] == 0xff.toByte() }
            )
        }
        masterKey.close()
    }

    @Test
    fun `cancelDevicePairing closes active MasterVaultKey`() = runTest {
        val masterKey = MasterVaultKey.generate()
        every { mockRepository.hasVaultKey() } returns false
        coEvery {
            mockDevicePairingManager.startReceiverFlow(any())
        } returns flowOf(
            PairingState.Success(masterKey, "ticket-test")
        )

        viewModel.startDevicePairing()
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.pairingState.value is PairingState.Success)
        assertFalse(masterKey.isClosed)

        viewModel.cancelDevicePairing()

        assertTrue(masterKey.isClosed)
        assertEquals(null, viewModel.pairingState.value)
    }

    @Test
    fun `linkWithScannedQr sets error on malformed qr code`() = runTest {
        viewModel.linkWithScannedQr("not-a-valid-json")
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value is AuthUiState.Error)
        assertTrue((viewModel.state.value as AuthUiState.Error).message.contains("Invalid pairing QR code"))
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }
}