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

@OptIn(ExperimentalCoroutinesApi::class)
class UserSetupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockPasskeyManager: PasskeyAuthManager
    private lateinit var viewModel: UserSetupViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockPasskeyManager = mockk(relaxed = true)
        viewModel = UserSetupViewModel(mockPasskeyManager)
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
        coEvery {
            mockPasskeyManager.registerPasskey(mockContext, any())
        } returns PasskeyRegisterResult.Success

        viewModel.enrollPasskey(mockContext)

        testScheduler.advanceUntilIdle()

        assertEquals(PasskeyEnrollmentState.Enrolled, viewModel.enrollmentState.value)
    }

    @Test
    fun `enrollPasskey cancelled transitions to Idle`() = runTest {
        val mockContext: Context = mockk()
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
        coEvery {
            mockPasskeyManager.registerPasskey(mockContext, any())
        } returns PasskeyRegisterResult.Error("Biometrics locked out")

        viewModel.enrollPasskey(mockContext)

        testScheduler.advanceUntilIdle()

        val state = viewModel.enrollmentState.value
        assertTrue(state is PasskeyEnrollmentState.Error)
        assertEquals("Biometrics locked out", (state as PasskeyEnrollmentState.Error).message)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }
}
