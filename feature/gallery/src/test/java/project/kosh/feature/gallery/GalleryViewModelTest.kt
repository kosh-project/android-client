package project.kosh.feature.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.database.crdt.MediaUploadPipeline
import project.kosh.core.database.crdt.UploadResult
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.database.repository.MediaRepository
import project.kosh.core.model.MediaAsset
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.AssetClient
import project.kosh.feature.gallery.loader.EncryptedThumbnailLoader
import project.kosh.feature.gallery.model.DiscoveredMediaItem
import project.kosh.feature.gallery.model.MediaFolder
import project.kosh.feature.gallery.sync.MediaFolderScanner
import project.kosh.feature.gallery.sync.SyncFolderPreferences

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mockAssetClient: AssetClient
    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var mockDevicePairingManager: DevicePairingManager
    private lateinit var mockMediaRepository: MediaRepository
    private lateinit var mockMediaUploadPipeline: MediaUploadPipeline
    private lateinit var mockMediaFolderScanner: MediaFolderScanner
    private lateinit var mockSyncFolderPreferences: SyncFolderPreferences
    private lateinit var mockThumbnailLoader: EncryptedThumbnailLoader
    private lateinit var viewModel: GalleryViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        mockAssetClient = mockk(relaxed = true)
        mockAuthRepository = mockk(relaxed = true)
        mockDevicePairingManager = mockk(relaxed = true)
        mockMediaRepository = mockk(relaxed = true)
        mockMediaUploadPipeline = mockk(relaxed = true)
        mockMediaFolderScanner = mockk(relaxed = true)
        mockSyncFolderPreferences = mockk(relaxed = true)
        mockThumbnailLoader = mockk(relaxed = true)

        every { mockAuthRepository.isLoggedIn() } returns true
        every { mockAuthRepository.getActiveUsername() } returns "testuser"
        every { mockSyncFolderPreferences.hasCompletedFolderSetup() } returns true
        every { mockMediaRepository.observeAllMediaWithCrypto() } returns flowOf(emptyList())

        viewModel = createViewModel()
    }

    private fun createViewModel(): GalleryViewModel {
        return GalleryViewModel(
            assetClient = mockAssetClient,
            authRepository = mockAuthRepository,
            devicePairingManager = mockDevicePairingManager,
            mediaRepository = mockMediaRepository,
            mediaUploadPipeline = mockMediaUploadPipeline,
            mediaFolderScanner = mockMediaFolderScanner,
            syncFolderPreferences = mockSyncFolderPreferences,
            thumbnailLoader = mockThumbnailLoader
        ).apply {
            ioDispatcher = testDispatcher
        }
    }

    @Test
    fun `initial state is idle`() {
        assertEquals(UploadUiState.Idle, viewModel.uploadState.value)
        assertEquals("Idle", viewModel.uploadStatus.value)
        assertFalse(viewModel.showPermissionRationale.value)
    }

    @Test
    fun `first time launch displays permission rationale if setup not completed`() {
        every { mockSyncFolderPreferences.hasCompletedFolderSetup() } returns false
        val newVm = createViewModel()
        assertTrue(newVm.showPermissionRationale.value)
    }

    @Test
    fun `isSessionActive checks authRepository`() {
        every { mockAuthRepository.isLoggedIn() } returns true
        assertTrue(viewModel.isSessionActive())

        every { mockAuthRepository.isLoggedIn() } returns false
        assertFalse(viewModel.isSessionActive())
    }

    @Test
    fun `logout delegates to authRepository and clears session states and memory cache`() {
        viewModel.logout()
        verify(exactly = 1) { mockAuthRepository.logout() }
        verify(exactly = 1) { mockThumbnailLoader.clearMemoryCache() }
        verify(exactly = 1) { mockThumbnailLoader.clearDiskCache() }
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
        val mockVaultKey: MasterVaultKey = mockk(relaxed = true)
        val mockAsset: MediaAsset = mockk(relaxed = true)

        every { mockAuthRepository.getVaultKey() } returns mockVaultKey
        coEvery {
            mockMediaUploadPipeline.uploadFromUri(
                contentResolver = any(),
                uri = any(),
                masterVaultKey = any(),
                mediaType = any(),
                originalFileName = any(),
                customFileId = any(),
                onProgress = any()
            )
        } returns UploadResult.Success(mockAsset, false)

        viewModel.uploadPhoto(mockResolver, mockUri)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uploadState.value
        assertTrue(state is UploadUiState.Success)
        assertEquals("Stored in vault", (state as UploadUiState.Success).message)
    }

    @Test
    fun `uploadPhoto failure updates uploadState to Error`() = runTest {
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val mockUri: Uri = mockk(relaxed = true)
        val mockVaultKey: MasterVaultKey = mockk(relaxed = true)

        every { mockAuthRepository.getVaultKey() } returns mockVaultKey
        coEvery {
            mockMediaUploadPipeline.uploadFromUri(
                contentResolver = any(),
                uri = any(),
                masterVaultKey = any(),
                mediaType = any(),
                originalFileName = any(),
                customFileId = any(),
                onProgress = any()
            )
        } returns UploadResult.Error("Network failure")

        viewModel.uploadPhoto(mockResolver, mockUri)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uploadState.value
        assertTrue(state is UploadUiState.Error)
        assertEquals("Network failure", (state as UploadUiState.Error).message)
    }

    @Test
    fun `uploadPhoto fails if master vault key is locked`() = runTest {
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val mockUri: Uri = mockk(relaxed = true)

        every { mockAuthRepository.getVaultKey() } returns null

        viewModel.uploadPhoto(mockResolver, mockUri)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uploadState.value
        assertTrue(state is UploadUiState.Error)
        assertEquals("Master Vault Key locked", (state as UploadUiState.Error).message)
    }

    @Test
    fun `loadFolders queries MediaFolderScanner and updates mediaFolders flow`() = runTest {
        val mockFolderList = listOf(
            MediaFolder(
                bucketId = "bucket-1",
                displayName = "Camera",
                mediaCount = 12,
                representativeUri = mockk(relaxed = true),
                isSelectedForSync = true
            )
        )
        coEvery { mockMediaFolderScanner.queryMediaFolders() } returns mockFolderList

        viewModel.loadFolders()
        testScheduler.advanceUntilIdle()

        assertEquals(mockFolderList, viewModel.mediaFolders.value)
        assertFalse(viewModel.isScanningFolders.value)
    }

    @Test
    fun `saveFolderSelection updates preferences and enqueues selected folder items`() = runTest {
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val mockVaultKey: MasterVaultKey = mockk(relaxed = true)
        val mockAsset: MediaAsset = mockk(relaxed = true)
        val testUri = mockk<Uri>(relaxed = true)

        every { mockAuthRepository.getVaultKey() } returns mockVaultKey
        coEvery {
            mockMediaFolderScanner.queryMediaInFolder("bucket-camera")
        } returns listOf(
            DiscoveredMediaItem(
                id = 101L,
                uri = testUri,
                displayName = "IMG_001.jpg",
                mimeType = "image/jpeg",
                size = 1024L,
                dateModified = 123456789L,
                bucketId = "bucket-camera"
            )
        )
        coEvery {
            mockMediaUploadPipeline.uploadFromUri(any(), any(), any(), any(), any(), any(), any())
        } returns UploadResult.Success(mockAsset, false)

        viewModel.saveFolderSelection(setOf("bucket-camera"), mockResolver)
        testScheduler.advanceUntilIdle()

        verify { mockSyncFolderPreferences.setEnabledBucketIds(setOf("bucket-camera")) }
        verify { mockSyncFolderPreferences.setFolderSetupCompleted(true) }
        assertFalse(viewModel.showFolderSelectionSheet.value)
        assertFalse(viewModel.showPermissionRationale.value)
    }

    @Test
    fun `folder selection UI toggles correctly`() = runTest {
        coEvery { mockMediaFolderScanner.queryMediaFolders() } returns emptyList()

        viewModel.openFolderSelection()
        assertTrue(viewModel.showFolderSelectionSheet.value)

        viewModel.dismissFolderSelection()
        assertFalse(viewModel.showFolderSelectionSheet.value)
    }

    @Test
    fun `onPermissionGranted dismisses rationale and opens folder selection`() = runTest {
        val mockResolver: ContentResolver = mockk(relaxed = true)
        coEvery { mockMediaFolderScanner.queryMediaFolders() } returns emptyList()

        viewModel.onPermissionGranted(mockResolver)
        assertFalse(viewModel.showPermissionRationale.value)
        assertTrue(viewModel.showFolderSelectionSheet.value)
    }

    @Test
    fun `dismissPermissionRationale marks setup completed and dismisses dialog`() {
        viewModel.dismissPermissionRationale()
        assertFalse(viewModel.showPermissionRationale.value)
        verify { mockSyncFolderPreferences.setFolderSetupCompleted(true) }
    }

    @Test
    fun `dismissBatchBar stops uploading indicator`() {
        viewModel.dismissBatchBar()
        assertFalse(viewModel.batchUploadState.value.isUploading)
    }

    @Test
    fun `loadThumbnail delegates to thumbnailLoader with master key`() = runTest {
        val mockItem: MediaWithCryptoDetails = mockk(relaxed = true)
        val mockKey: MasterVaultKey = mockk(relaxed = true)
        val mockBitmap: Bitmap = mockk(relaxed = true)

        every { mockAuthRepository.getVaultKey() } returns mockKey
        coEvery { mockThumbnailLoader.loadThumbnail(mockItem, mockKey) } returns mockBitmap

        val result = viewModel.loadThumbnail(mockItem)
        assertEquals(mockBitmap, result)
        coVerify(exactly = 1) { mockThumbnailLoader.loadThumbnail(mockItem, mockKey) }
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

    @Test
    fun `enqueueUploads adds in-flight item to galleryItems and reports progress stages`() = runTest {
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val mockUri: Uri = mockk(relaxed = true)
        val mockVaultKey: MasterVaultKey = mockk(relaxed = true)
        val testAsset = MediaAsset(fileId = "persisted-file-id", mediaType = "image/jpeg", fileSize = 100L, capturedAt = 1000L)

        every { mockAuthRepository.getVaultKey() } returns mockVaultKey
        coEvery {
            mockMediaUploadPipeline.uploadFromUri(
                contentResolver = any(),
                uri = any(),
                masterVaultKey = any(),
                mediaType = any(),
                originalFileName = any(),
                customFileId = any(),
                onProgress = any()
            )
        } answers {
            val fileId = arg<String?>(5)
            assertNotNull(fileId)

            val progressCallback = arg<((project.kosh.core.model.UploadStage) -> Unit)?>(6)
            progressCallback?.invoke(project.kosh.core.model.UploadStage.Hashing(0.5f))
            assertEquals(project.kosh.core.model.UploadStage.Hashing(0.5f), viewModel.activeUploadStages.value[fileId])

            progressCallback?.invoke(project.kosh.core.model.UploadStage.Uploading(0.8f, 800L, 1000L, 50000L))
            assertEquals(
                project.kosh.core.model.UploadStage.Uploading(0.8f, 800L, 1000L, 50000L),
                viewModel.activeUploadStages.value[fileId]
            )

            UploadResult.Success(testAsset, false)
        }

        viewModel.enqueueUploads(mockResolver, listOf(mockUri))
        testScheduler.advanceUntilIdle()

        val finalUploadState = viewModel.uploadState.value
        assertTrue(finalUploadState is UploadUiState.Success)
        assertEquals("Stored in vault", (finalUploadState as UploadUiState.Success).message)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }
}
