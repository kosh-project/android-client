package project.kosh.feature.auth

import android.content.Context
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import project.kosh.core.auth.PasskeyAuthManager
import project.kosh.core.auth.PasskeyRegisterResult

import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import project.kosh.core.auth.PasskeyLoginResult
import project.kosh.core.auth.SessionManager

@OptIn(ExperimentalCoroutinesApi::class)
class UserSetupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockPasskeyManager: PasskeyAuthManager
    private lateinit var mockSessionManager: SessionManager
    private lateinit var viewModel: UserSetupViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockPasskeyManager = mockk(relaxed = true)
        mockSessionManager = mockk(relaxed = true)
        viewModel = UserSetupViewModel(mockPasskeyManager, mockSessionManager)
    }

    @Test
    fun `initial state has device nickname and idle enrollment`() {
        assertTrue(viewModel.deviceNickname.value.isNotBlank())
        assertEquals(PasskeyEnrollmentState.Idle, viewModel.enrollmentState.value)
    }

    @Test
    fun `updateNickname updates the device nickname`() {
        viewModel.updateNickname("My Pixel Tablet")
        assertEquals("My Pixel Tablet", viewModel.deviceNickname.value)
    }

    @Test
    fun `enrollPasskey success transitions to Enrolled`() = runTest {
        val mockContext: Context = mockk()
        every { mockSessionManager.getToken() } returns "active-token"
        coEvery {
            mockPasskeyManager.registerPasskey(mockContext, any())
        } returns PasskeyRegisterResult.Success

        viewModel.enrollPasskey(mockContext)

        testScheduler.advanceUntilIdle()

        assertEquals(PasskeyEnrollmentState.Enrolled, viewModel.enrollmentState.value)
    }

    @Test
    fun `enrollPasskey with registration ticket executes Mode 2 registration and login`() = runTest {
        val mockContext: Context = mockk()
        val testTicket = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        every { mockSessionManager.getToken() } returns null
        every { mockSessionManager.getRegistrationTicket() } returns testTicket

        coEvery {
            mockPasskeyManager.registerPasskeyWithTicket(mockContext, testTicket, any())
        } returns PasskeyRegisterResult.Success

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext)
        } returns PasskeyLoginResult.Success("session-token", "dummy-wrapped-key", null)

        viewModel.enrollPasskey(mockContext)

        testScheduler.advanceUntilIdle()

        assertEquals(PasskeyEnrollmentState.Enrolled, viewModel.enrollmentState.value)
        coVerify(exactly = 1) { mockPasskeyManager.registerPasskeyWithTicket(mockContext, testTicket, any()) }
        verify(exactly = 1) { mockSessionManager.clearRegistrationTicket() }
        coVerify(exactly = 1) { mockPasskeyManager.loginWithPasskey(mockContext) }
    }

    @Test
    fun `enrollPasskey cancelled transitions to Idle`() = runTest {
        val mockContext: Context = mockk()
        every { mockSessionManager.getToken() } returns "active-token"
        coEvery {
            mockPasskeyManager.registerPasskey(mockContext, any())
        } returns PasskeyRegisterResult.Cancelled

        viewModel.enrollPasskey(mockContext)

        testScheduler.advanceUntilIdle()

        assertEquals(PasskeyEnrollmentState.Idle, viewModel.enrollmentState.value)
    }

    @Test
    fun `enrollPasskey error transitions to Error with message`() = runTest {
        val mockContext: Context = mockk()
        every { mockSessionManager.getToken() } returns "active-token"
        coEvery {
            mockPasskeyManager.registerPasskey(mockContext, any())
        } returns PasskeyRegisterResult.Error("Biometrics locked out")

        viewModel.enrollPasskey(mockContext)

        testScheduler.advanceUntilIdle()

        val state = viewModel.enrollmentState.value
        assertTrue(state is PasskeyEnrollmentState.Error)
        assertEquals("Biometrics locked out", (state as PasskeyEnrollmentState.Error).message)
    }

    @Test
    fun `enrollPasskey retries login when passkey is already enrolled on authenticator`() = runTest {
        val mockContext: Context = mockk()
        every { mockSessionManager.getToken() } returns null
        every { mockSessionManager.getRegistrationTicket() } returns null
        every { mockPasskeyManager.hasEnrolledPasskeys() } returns true

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext)
        } returns PasskeyLoginResult.Success("session-token", "dummy-wrapped-key", null)

        viewModel.enrollPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        assertEquals(PasskeyEnrollmentState.Enrolled, viewModel.enrollmentState.value)
        coVerify(exactly = 0) { mockPasskeyManager.registerPasskey(any(), any()) }
        coVerify(exactly = 1) { mockPasskeyManager.loginWithPasskey(mockContext) }
    }

    @Test
    fun `enrollPasskey with ticket transitions to Idle when loginWithPasskey is cancelled`() = runTest {
        val mockContext: Context = mockk()
        val testTicket = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        every { mockSessionManager.getToken() } returns null
        every { mockSessionManager.getRegistrationTicket() } returns testTicket

        coEvery {
            mockPasskeyManager.registerPasskeyWithTicket(mockContext, testTicket, any())
        } returns PasskeyRegisterResult.Success

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext)
        } returns PasskeyLoginResult.Cancelled

        viewModel.enrollPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        assertEquals(PasskeyEnrollmentState.Idle, viewModel.enrollmentState.value)
        verify(exactly = 1) { mockSessionManager.clearRegistrationTicket() }
    }

    @Test
    fun `enrollPasskey with ticket transitions to Error when loginWithPasskey fails`() = runTest {
        val mockContext: Context = mockk()
        val testTicket = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        every { mockSessionManager.getToken() } returns null
        every { mockSessionManager.getRegistrationTicket() } returns testTicket

        coEvery {
            mockPasskeyManager.registerPasskeyWithTicket(mockContext, testTicket, any())
        } returns PasskeyRegisterResult.Success

        coEvery {
            mockPasskeyManager.loginWithPasskey(mockContext)
        } returns PasskeyLoginResult.Error("Network handshake timeout")

        viewModel.enrollPasskey(mockContext)
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.enrollmentState.value is PasskeyEnrollmentState.Error)
        assertEquals("Network handshake timeout", (viewModel.enrollmentState.value as PasskeyEnrollmentState.Error).message)
        verify(exactly = 1) { mockSessionManager.clearRegistrationTicket() }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }
}
