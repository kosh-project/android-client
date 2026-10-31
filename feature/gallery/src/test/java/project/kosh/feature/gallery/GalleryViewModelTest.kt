package project.kosh.feature.gallery

import android.content.ContentResolver
import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import project.kosh.core.auth.AuthRepository
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.AssetClient

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockAssetClient: AssetClient
    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var viewModel: GalleryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockAssetClient = mockk(relaxed = true)
        mockAuthRepository = mockk(relaxed = true)

        viewModel = GalleryViewModel(mockAssetClient, mockAuthRepository).apply {
            ioDispatcher = testDispatcher
        }
    }

    @Test
    fun `initial state is idle`() {
        assertEquals(UploadUiState.Idle, viewModel.uploadState.value)
        assertEquals("Idle", viewModel.uploadStatus.value)
    }

    @Test
    fun `isSessionActive checks authRepository`() {
        every { mockAuthRepository.isLoggedIn() } returns true
        assertTrue(viewModel.isSessionActive())

        every { mockAuthRepository.isLoggedIn() } returns false
        assertFalse(viewModel.isSessionActive())
    }

    @Test
    fun `logout delegates to authRepository and clears session states`() {
        viewModel.logout()
        verify(exactly = 1) { mockAuthRepository.logout() }
        assertFalse(viewModel.isSessionActive.value)
        assertEquals(null, viewModel.activeUser.value)
    }

    @Test
    fun `dismissUploadState resets upload state to Idle`() {
        viewModel.dismissUploadState()
        assertEquals(UploadUiState.Idle, viewModel.uploadState.value)
        assertEquals("Idle", viewModel.uploadStatus.value)
    }

    @Test
    fun `uploadPhoto success updates uploadState to Success`() = runTest {
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val mockUri: Uri = mockk(relaxed = true)

        coEvery {
            mockAssetClient.uploadAsset(any(), any(), any(), any())
        } returns NetworkResult.Success("hash123")

        viewModel.uploadPhoto(mockResolver, mockUri)

        testScheduler.advanceUntilIdle()

        val state = viewModel.uploadState.value
        assertTrue(state is UploadUiState.Success)
        assertEquals("Success: 201 Created!", viewModel.uploadStatus.value)
    }

    @Test
    fun `uploadPhoto failure updates uploadState to Error`() = runTest {
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val mockUri: Uri = mockk(relaxed = true)

        coEvery {
            mockAssetClient.uploadAsset(any(), any(), any(), any())
        } returns NetworkResult.Unknown("Connection reset", RuntimeException("Timeout"))

        viewModel.uploadPhoto(mockResolver, mockUri)

        testScheduler.advanceUntilIdle()

        val state = viewModel.uploadState.value
        assertTrue(state is UploadUiState.Error)
        assertEquals("Network error: Connection reset", (state as UploadUiState.Error).message)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }
}
