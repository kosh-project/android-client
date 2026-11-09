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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import project.kosh.core.auth.AuthRepository
import project.kosh.core.auth.DevicePairingManager
import project.kosh.core.auth.PairingState
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.AssetClient
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockAssetClient: AssetClient
    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var mockDevicePairingManager: DevicePairingManager
    private lateinit var viewModel: GalleryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockAssetClient = mockk(relaxed = true)
        mockAuthRepository = mockk(relaxed = true)
        mockDevicePairingManager = mockk(relaxed = true)

        viewModel = GalleryViewModel(mockAssetClient, mockAuthRepository, mockDevicePairingManager).apply {
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

    @Test
    fun `startHostPairing collects host flow and updates pairingState`() = runTest {
        coEvery { mockDevicePairingManager.startHostFlow(any(), any()) } returns flowOf(
            PairingState.AwaitingJoin("test-mbx", "{\"mailbox_id\":\"test-mbx\"}", 300L)
        )

        viewModel.startHostPairing()
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.pairingState.value is PairingState.AwaitingJoin)
        assertEquals("test-mbx", (viewModel.pairingState.value as PairingState.AwaitingJoin).mailboxId)
    }

    @Test
    fun `startHostPairing populates activePairingSession and stopHostPairing clears it`() = runTest {
        coEvery { mockDevicePairingManager.startHostFlow(any(), any()) } returns flowOf(
            PairingState.AwaitingJoin("test-mbx", "{\"mailbox_id\":\"test-mbx\"}", 300L)
        )

        viewModel.startHostPairing()
        testScheduler.advanceUntilIdle()

        val session = viewModel.activePairingSession.value
        assertNotNull(session)
        assertEquals("test-mbx", session?.mailboxId)
        assertEquals(300L, session?.expiresInSeconds)

        viewModel.stopHostPairing()
        assertNull(viewModel.activePairingSession.value)
        assertNull(viewModel.pairingState.value)
    }

    @Test
    fun `fulfillReceiverQr fulfills receiver session and sets success message`() = runTest {
        val qrJson = """{"mailbox_id":"rx-1234","public_key":"aabbcc","mode":"receiver"}"""
        coEvery {
            mockDevicePairingManager.fulfillSenderSession(
                mailboxId = "rx-1234",
                receiverPublicKeyHex = "aabbcc",
                token = null
            )
        } returns NetworkResult.Success(Unit)

        viewModel.fulfillReceiverQr(qrJson)
        testScheduler.advanceUntilIdle()

        assertEquals("Device successfully linked!", viewModel.pairingSuccessMessage.value)
    }

    @Test
    fun `fulfillReceiverCode with valid 8-char code calls fulfillSenderWithCode and sets success`() = runTest {
        coEvery {
            mockDevicePairingManager.fulfillSenderWithCode("c7m9x4b2", null, null)
        } returns NetworkResult.Success(Unit)

        viewModel.fulfillReceiverCode("c7m9-x4b2")
        testScheduler.advanceUntilIdle()

        assertEquals("Device successfully linked!", viewModel.pairingSuccessMessage.value)
        assertEquals(null, viewModel.pairingState.value)
    }

    @Test
    fun `fulfillReceiverCode with invalid length sets pairing error`() = runTest {
        viewModel.fulfillReceiverCode("short")
        testScheduler.advanceUntilIdle()

        assertTrue(viewModel.pairingState.value is PairingState.Error)
        assertEquals("Pairing code must be 8 characters", (viewModel.pairingState.value as PairingState.Error).message)
    }

    @Test
    fun `fulfillReceiverQr with plain 8-character code calls fulfillReceiverCode`() = runTest {
        coEvery {
            mockDevicePairingManager.fulfillSenderWithCode("k8n2p4x9", null, null)
        } returns NetworkResult.Success(Unit)

        viewModel.fulfillReceiverQr("k8n2-p4x9")
        testScheduler.advanceUntilIdle()

        assertEquals("Device successfully linked!", viewModel.pairingSuccessMessage.value)
    }

    @Test
    fun `stopHostPairing clears pairing state and success message`() = runTest {
        viewModel.stopHostPairing()
        assertEquals(null, viewModel.pairingState.value)
        assertEquals(null, viewModel.pairingSuccessMessage.value)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }
}
