package project.kosh.feature.auth

import androidx.lifecycle.viewmodel.compose.viewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import project.kosh.core.auth.AuthRepository
import project.kosh.core.crypto.IdentityManager
import project.kosh.core.model.AuthCredential
import project.kosh.core.model.NetworkResult

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockRepository: AuthRepository
    private lateinit var mockIdentityManager: IdentityManager

    private lateinit var viewModel: AuthViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockRepository = mockk()
        mockIdentityManager = mockk()

        viewModel = AuthViewModel(mockRepository, mockIdentityManager).apply {
            defaultDispatcher = testDispatcher
        }
    }

    @Test
    fun `when network fails, state becomes error`() = runTest {
        assertTrue(viewModel.state.value is AuthUiState.Idle)

        val fakeCredential = AuthCredential("hash", "verifier")

        coEvery {
            mockIdentityManager.deriveCredentials(any(), any())
        } returns fakeCredential

        coEvery {
            mockRepository.login(any())
        } returns NetworkResult.fromHttp(401, "Unauthorized")

        viewModel.login("user", "pass")

        testScheduler.advanceUntilIdle()

        val finalState = viewModel.state.value
        assertTrue("State should be Error, but was $finalState", finalState is AuthUiState.Error)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }
}