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
import project.kosh.core.database.crdt.CrdtSyncEngine
import project.kosh.core.database.crdt.MediaUploadPipeline
import project.kosh.core.database.crdt.SnapshotSyncManager
import project.kosh.core.database.crdt.SyncResult
import project.kosh.core.database.crdt.UploadResult
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.database.repository.MediaRepository
import project.kosh.core.model.MediaAsset
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.SyncNamespace
import project.kosh.core.model.UploadStage
import project.kosh.core.model.UploadStatus
import project.kosh.core.network.AssetClient
import project.kosh.feature.gallery.loader.EncryptedThumbnailLoader
import project.kosh.feature.gallery.model.DiscoveredMediaItem
import project.kosh.feature.gallery.model.MediaFolder
import project.kosh.feature.gallery.sync.MediaFolderScanner
import project.kosh.feature.gallery.sync.SnapshotWorkManagerScheduler
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
    private lateinit var mockCrdtSyncEngine: CrdtSyncEngine
    private lateinit var mockSnapshotSyncManager: SnapshotSyncManager
    private lateinit var mockMediaAssetDao: MediaAssetDao
    private lateinit var mockSnapshotScheduler: SnapshotWorkManagerScheduler
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
        mockCrdtSyncEngine = mockk(relaxed = true)
        mockSnapshotSyncManager = mockk(relaxed = true)
        mockMediaAssetDao = mockk(relaxed = true)
        mockSnapshotScheduler = mockk(relaxed = true)

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
            thumbnailLoader = mockThumbnailLoader,
            crdtSyncEngine = mockCrdtSyncEngine,
            snapshotSyncManager = mockSnapshotSyncManager,
            mediaAssetDao = mockMediaAssetDao,
            snapshotScheduler = mockSnapshotScheduler
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
                customCapturedAt = any(),
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
                customCapturedAt = any(),
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
            mockMediaUploadPipeline.uploadFromUri(any(), any(), any(), any(), any(), any(), any(), any())
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
                customCapturedAt = any(),
                onProgress = any()
            )
        } answers {
            val fileId = arg<String?>(5)
            assertNotNull(fileId)

            val progressCallback = arg<((project.kosh.core.model.UploadStage) -> Unit)?>(7)
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

    @Test
    fun `InFlightUploadItem preserves metadata and maps correctly to MediaWithCryptoDetails`() {
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val mockUri: Uri = mockk(relaxed = true)
        val inFlight = InFlightUploadItem(
            fileId = "item-test-uuid",
            uri = mockUri,
            fileName = "vacation.png",
            contentResolver = mockResolver,
            dateModified = 1700000000000L,
            mimeType = "image/png",
            fileSize = 4096L
        )

        val mediaWithCrypto = inFlight.toMediaWithCryptoDetails()

        assertEquals("item-test-uuid", mediaWithCrypto.asset.fileId)
        assertEquals("image/png", mediaWithCrypto.asset.mediaType)
        assertEquals(4096L, mediaWithCrypto.asset.fileSize)
        assertEquals(1700000000000L, mediaWithCrypto.asset.capturedAt)
        assertEquals(UploadStatus.UPLOADING, mediaWithCrypto.cryptoMapping?.uploadStatus)
    }

    @Test
    fun `saveFolderSelection passes task dateModified as customCapturedAt to uploadFromUri`() = runTest {
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val mockVaultKey: MasterVaultKey = mockk(relaxed = true)
        val mockAsset: MediaAsset = mockk(relaxed = true)
        val testUri = mockk<Uri>(relaxed = true)
        val capturedAtCaptured = mutableListOf<Long?>()
        val mediaTypeCaptured = mutableListOf<String?>()

        every { mockAuthRepository.getVaultKey() } returns mockVaultKey
        coEvery {
            mockMediaFolderScanner.queryMediaInFolder("camera-bucket")
        } returns listOf(
            DiscoveredMediaItem(
                id = 999L,
                uri = testUri,
                displayName = "sunset.jpg",
                mimeType = "image/jpeg",
                size = 2048L,
                dateModified = 1699999999000L,
                bucketId = "camera-bucket"
            )
        )

        coEvery {
            mockMediaUploadPipeline.uploadFromUri(
                contentResolver = any(),
                uri = any(),
                masterVaultKey = any(),
                mediaType = any(),
                originalFileName = any(),
                customFileId = any(),
                customCapturedAt = any(),
                onProgress = any()
            )
        } answers {
            mediaTypeCaptured.add(arg<String?>(3))
            capturedAtCaptured.add(arg<Long?>(6))
            UploadResult.Success(mockAsset, false)
        }

        viewModel.saveFolderSelection(setOf("camera-bucket"), mockResolver)
        testScheduler.advanceUntilIdle()

        assertEquals(1, capturedAtCaptured.size)
        assertEquals(1699999999000L, capturedAtCaptured[0])
        assertEquals("image/jpeg", mediaTypeCaptured[0])
    }

    @Test
    fun `galleryItems sorts in-flight and persisted items deterministically by capturedAt desc then fileId desc`() = runTest {
        val p1 = MediaWithCryptoDetails(
            asset = project.kosh.core.database.entity.MediaAssetEntity(
                fileId = "persisted-old",
                mediaType = "image/jpeg",
                fileSize = 100L,
                capturedAt = 1000L
            ),
            cryptoMapping = null
        )
        val p2 = MediaWithCryptoDetails(
            asset = project.kosh.core.database.entity.MediaAssetEntity(
                fileId = "persisted-new",
                mediaType = "image/jpeg",
                fileSize = 200L,
                capturedAt = 3000L
            ),
            cryptoMapping = null
        )
        val p3SameTimeA = MediaWithCryptoDetails(
            asset = project.kosh.core.database.entity.MediaAssetEntity(
                fileId = "persisted-same-a",
                mediaType = "image/jpeg",
                fileSize = 300L,
                capturedAt = 2000L
            ),
            cryptoMapping = null
        )

        every { mockMediaRepository.observeAllMediaWithCrypto() } returns flowOf(listOf(p1, p2, p3SameTimeA))

        val vm = createViewModel()
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val testUri = mockk<Uri>(relaxed = true)
        val mockVaultKey: MasterVaultKey = mockk(relaxed = true)
        every { mockAuthRepository.getVaultKey() } returns mockVaultKey

        coEvery {
            mockMediaUploadPipeline.uploadFromUri(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            kotlinx.coroutines.delay(10000L)
            UploadResult.Success(mockk(relaxed = true), false)
        }

        coEvery {
            mockMediaFolderScanner.queryMediaInFolder("camera-bucket")
        } returns listOf(
            DiscoveredMediaItem(
                id = 555L,
                uri = testUri,
                displayName = "inflight.jpg",
                mimeType = "image/jpeg",
                size = 150L,
                dateModified = 2000L,
                bucketId = "camera-bucket"
            )
        )

        val itemsJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.galleryItems.collect()
        }

        vm.saveFolderSelection(setOf("camera-bucket"), mockResolver)
        testScheduler.runCurrent()

        val items = vm.galleryItems.value
        assertEquals(4, items.size)
        // 1st: capturedAt = 3000L ("persisted-new")
        assertEquals(3000L, items[0].asset.capturedAt)
        assertEquals("persisted-new", items[0].asset.fileId)
        // 2nd and 3rd: capturedAt = 2000L, sorted by fileId descending
        assertEquals(2000L, items[1].asset.capturedAt)
        assertEquals(2000L, items[2].asset.capturedAt)
        assertTrue(items[1].asset.fileId > items[2].asset.fileId)
        // 4th: capturedAt = 1000L ("persisted-old")
        assertEquals(1000L, items[3].asset.capturedAt)
        assertEquals("persisted-old", items[3].asset.fileId)

        itemsJob.cancel()
    }

    @Test
    fun `batch upload resets isUploading to false and counts to zero when batch finishes`() = runTest {
        val vm = createViewModel()
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val testUri = mockk<Uri>(relaxed = true)
        val mockVaultKey: MasterVaultKey = mockk(relaxed = true)
        every { mockAuthRepository.getVaultKey() } returns mockVaultKey

        coEvery {
            mockMediaUploadPipeline.uploadFromUri(any(), any(), any(), any(), any(), any(), any(), any())
        } returns UploadResult.Success(mockk(relaxed = true), false)

        coEvery {
            mockMediaFolderScanner.queryMediaInFolder("camera-bucket")
        } returns listOf(
            DiscoveredMediaItem(
                id = 101L,
                uri = testUri,
                displayName = "photo.jpg",
                mimeType = "image/jpeg",
                size = 100L,
                dateModified = 1000L,
                bucketId = "camera-bucket"
            )
        )

        vm.saveFolderSelection(setOf("camera-bucket"), mockResolver)
        testScheduler.advanceUntilIdle()

        assertFalse(vm.batchUploadState.value.isUploading)
        assertEquals(0, vm.batchUploadState.value.totalItems)
        assertEquals(0, vm.batchUploadState.value.completedItems)
    }

    @Test
    fun `saveFolderSelection ignores URIs that are already in flight`() = runTest {
        val vm = createViewModel()
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val testUri = mockk<Uri>(relaxed = true)
        val mockVaultKey: MasterVaultKey = mockk(relaxed = true)
        every { mockAuthRepository.getVaultKey() } returns mockVaultKey

        coEvery {
            mockMediaUploadPipeline.uploadFromUri(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            kotlinx.coroutines.delay(10000L)
            UploadResult.Success(mockk(relaxed = true), false)
        }

        coEvery {
            mockMediaFolderScanner.queryMediaInFolder("camera-bucket")
        } returns listOf(
            DiscoveredMediaItem(
                id = 101L,
                uri = testUri,
                displayName = "photo.jpg",
                mimeType = "image/jpeg",
                size = 100L,
                dateModified = 1000L,
                bucketId = "camera-bucket"
            )
        )

        // First folder selection enqueues the item
        vm.saveFolderSelection(setOf("camera-bucket"), mockResolver)
        testScheduler.runCurrent()
        assertEquals(1, vm.batchUploadState.value.totalItems)

        // Second folder selection with same URI should be ignored and not double-queued
        vm.saveFolderSelection(setOf("camera-bucket"), mockResolver)
        testScheduler.runCurrent()
        assertEquals(1, vm.batchUploadState.value.totalItems)
    }

    @Test
    fun `processSingleUpload cleans up in-flight item when master key is locked`() = runTest {
        val vm = createViewModel()
        val mockResolver: ContentResolver = mockk(relaxed = true)
        val testUri = mockk<Uri>(relaxed = true)
        every { mockAuthRepository.getVaultKey() } returns null

        coEvery {
            mockMediaFolderScanner.queryMediaInFolder("camera-bucket")
        } returns listOf(
            DiscoveredMediaItem(
                id = 101L,
                uri = testUri,
                displayName = "photo.jpg",
                mimeType = "image/jpeg",
                size = 100L,
                dateModified = 1000L,
                bucketId = "camera-bucket"
            )
        )

        vm.saveFolderSelection(setOf("camera-bucket"), mockResolver)
        testScheduler.advanceUntilIdle()

        assertTrue(vm.uploadState.value is UploadUiState.Error)
        assertEquals(0, vm.galleryItems.value.size)
    }

    @Test
    fun `90s ticker triggers crdtSyncEngine sync when pending queue count is positive`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 1.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockCrdtSyncEngine.getPendingQueueCount("gallery") } returns 3
        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } returns SyncResult(0, 0, 0, 1, 3)

        val contentResolver: ContentResolver = mockk(relaxed = true)
        val uri: Uri = mockk(relaxed = true)
        every { uri.toString() } returns "content://media/ticker_test"
        every { contentResolver.getType(uri) } returns "image/jpeg"

        // Make upload delay longer than 90s in virtual time
        coEvery { mockMediaUploadPipeline.uploadFromUri(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(120_000L)
            UploadResult.Success(
                MediaAsset(
                    fileId = "ticker-file",
                    thumbnailHash = null,
                    mediaType = "image/jpeg",
                    fileSize = 100L,
                    capturedAt = 1000L
                ),
                isDeduplicated = false
            )
        }

        viewModel.enqueueUploads(contentResolver, listOf(uri))
        testDispatcher.scheduler.advanceTimeBy(45_000L)
        coVerify(exactly = 0) { mockCrdtSyncEngine.sync(any(), any()) }

        testDispatcher.scheduler.advanceTimeBy(50_000L) // Total 95s > 90s tumble window
        coVerify(atLeast = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }

        testDispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `batch completion triggers final immediate crdtSyncEngine sync`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 2.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockCrdtSyncEngine.getPendingQueueCount("gallery") } returns 0
        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } returns SyncResult(0, 0, 0, 0, 0)

        val contentResolver: ContentResolver = mockk(relaxed = true)
        val uri: Uri = mockk(relaxed = true)
        every { uri.toString() } returns "content://media/batch_complete"
        every { contentResolver.getType(uri) } returns "image/jpeg"

        coEvery { mockMediaUploadPipeline.uploadFromUri(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            UploadResult.Success(
                MediaAsset(
                    fileId = "batch-complete-file",
                    thumbnailHash = null,
                    mediaType = "image/jpeg",
                    fileSize = 100L,
                    capturedAt = 1000L
                ),
                isDeduplicated = false
            )

        viewModel.enqueueUploads(contentResolver, listOf(uri))
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(atLeast = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }
        assertEquals(UploadStage.Completed, viewModel.batchUploadState.value.currentStage)
        assertFalse(viewModel.batchUploadState.value.isUploading)
    }

    @Test
    fun `error isolation ensures ledger sync failure does not disrupt CAS upload worker`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 3.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockCrdtSyncEngine.getPendingQueueCount("gallery") } returns 1
        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } throws RuntimeException("Ledger server network error")

        val contentResolver: ContentResolver = mockk(relaxed = true)
        val uri: Uri = mockk(relaxed = true)
        every { uri.toString() } returns "content://media/error_isolation"
        every { contentResolver.getType(uri) } returns "image/jpeg"

        coEvery { mockMediaUploadPipeline.uploadFromUri(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            UploadResult.Success(
                MediaAsset(
                    fileId = "isolated-file",
                    thumbnailHash = null,
                    mediaType = "image/jpeg",
                    fileSize = 100L,
                    capturedAt = 1000L
                ),
                isDeduplicated = false
            )

        viewModel.enqueueUploads(contentResolver, listOf(uri))
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify upload pipeline completed successfully despite sync throwing
        coVerify(exactly = 1) { mockMediaUploadPipeline.uploadFromUri(any(), any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(UploadStage.Completed, viewModel.batchUploadState.value.currentStage)
        assertFalse(viewModel.batchUploadState.value.isUploading)
    }

    @Test
    fun `checkAndRestoreSnapshot restores snapshot and triggers sync when local asset count is zero`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 4.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockMediaAssetDao.getCount() } returns 0
        coEvery { mockSnapshotSyncManager.restoreFromLatestSnapshot(vaultKey, SyncNamespace.GALLERY) } returns NetworkResult.Success(true)
        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } returns SyncResult(0, 0, 0, 0, 0)

        viewModel.checkAndRestoreSnapshot().join()

        coVerify(exactly = 1) { mockSnapshotSyncManager.restoreFromLatestSnapshot(vaultKey, SyncNamespace.GALLERY) }
        coVerify(exactly = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }
    }

    @Test
    fun `checkAndRestoreSnapshot triggers delta sync when snapshot does not exist on server`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 4.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockMediaAssetDao.getCount() } returns 0
        coEvery { mockSnapshotSyncManager.restoreFromLatestSnapshot(vaultKey, SyncNamespace.GALLERY) } returns NetworkResult.Success(false)
        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } returns SyncResult(0, 0, 0, 0, 0)

        viewModel.checkAndRestoreSnapshot().join()

        coVerify(exactly = 1) { mockSnapshotSyncManager.restoreFromLatestSnapshot(vaultKey, SyncNamespace.GALLERY) }
        coVerify(exactly = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }
    }

    @Test
    fun `checkAndRestoreSnapshot triggers delta sync when snapshot restoration fails`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 4.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockMediaAssetDao.getCount() } returns 0
        coEvery { mockSnapshotSyncManager.restoreFromLatestSnapshot(vaultKey, SyncNamespace.GALLERY) } returns NetworkResult.Http.NotFound(serverMessage = "No snapshot")
        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } returns SyncResult(0, 0, 0, 0, 0)

        viewModel.checkAndRestoreSnapshot().join()

        coVerify(exactly = 1) { mockSnapshotSyncManager.restoreFromLatestSnapshot(vaultKey, SyncNamespace.GALLERY) }
        coVerify(exactly = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }
    }

    @Test
    fun `checkAndRestoreSnapshot triggers delta sync directly when local asset count is greater than zero`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 4.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockMediaAssetDao.getCount() } returns 15
        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } returns SyncResult(0, 0, 0, 0, 0)
        io.mockk.clearMocks(mockSnapshotSyncManager, answers = false)

        viewModel.checkAndRestoreSnapshot().join()

        coVerify(exactly = 0) { mockSnapshotSyncManager.restoreFromLatestSnapshot(any(), any()) }
        coVerify(atLeast = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }
    }

    @Test
    fun `logout cancels periodic snapshot scheduler`() {
        viewModel.logout()
        io.mockk.verify(exactly = 1) { mockSnapshotScheduler.cancelPeriodicSnapshot() }
    }

    @Test
    fun `init schedules periodic snapshot when user is logged in`() {
        io.mockk.verify(atLeast = 1) { mockSnapshotScheduler.schedulePeriodicSnapshot() }
    }

    @Test
    fun `triggerManualSync runs crdtSyncEngine sync`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 5.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } returns SyncResult(0, 0, 0, 0, 0)

        viewModel.triggerManualSync().join()

        coVerify(exactly = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }
    }

    @Test
    fun `triggerManualSync sets isManualRefreshing and isSyncing to true during sync and resets to false`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 5.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey

        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } coAnswers {
            assertTrue(viewModel.isManualRefreshing.value)
            assertTrue(viewModel.isSyncing.value)
            SyncResult(0, 0, 0, 0, 0)
        }

        assertFalse(viewModel.isManualRefreshing.value)
        assertFalse(viewModel.isSyncing.value)

        viewModel.triggerManualSync().join()

        coVerify(exactly = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }
        assertFalse(viewModel.isManualRefreshing.value)
        assertFalse(viewModel.isSyncing.value)
    }

    @Test
    fun `triggerManualSync handles sync failure safely and resets isSyncing to false`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 5.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } throws RuntimeException("Server unreachable")

        viewModel.triggerManualSync().join()

        coVerify(exactly = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }
        assertFalse(viewModel.isManualRefreshing.value)
        assertFalse(viewModel.isSyncing.value)
    }

    @Test
    fun `triggerManualSync returns immediately when master vault key is locked`() = runTest {
        every { mockAuthRepository.getVaultKey() } returns null

        viewModel.triggerManualSync().join()

        coVerify(exactly = 0) { mockCrdtSyncEngine.sync(any(), any()) }
        assertFalse(viewModel.isManualRefreshing.value)
        assertFalse(viewModel.isSyncing.value)
    }

    @Test
    fun `triggerManualSync ignores concurrent invocations when sync is already in flight`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 5.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey

        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } coAnswers {
            kotlinx.coroutines.delay(5000L)
            SyncResult(0, 0, 0, 0, 0)
        }

        val job1 = viewModel.triggerManualSync()
        testScheduler.runCurrent()
        assertTrue(viewModel.isSyncing.value)

        val job2 = viewModel.triggerManualSync()
        testScheduler.runCurrent()

        testScheduler.advanceUntilIdle()
        job1.join()
        job2.join()

        coVerify(exactly = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }
        assertFalse(viewModel.isSyncing.value)
    }

    @Test
    fun `triggerManualSync bounds long running sync to 2000ms timeout`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 5.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey

        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } coAnswers {
            kotlinx.coroutines.delay(10_000L)
            SyncResult(0, 0, 0, 0, 0)
        }

        val job = viewModel.triggerManualSync()
        testScheduler.runCurrent()
        assertTrue(viewModel.isSyncing.value)

        // Advance 1999ms - should still be syncing
        testScheduler.advanceTimeBy(1999L)
        testScheduler.runCurrent()
        assertTrue(viewModel.isSyncing.value)

        // Advance past 2000ms ceiling - timeout should trigger and reset syncing state
        testScheduler.advanceTimeBy(2L)
        testScheduler.runCurrent()
        job.join()
        assertFalse(viewModel.isSyncing.value)
    }

    @Test
    fun `logout cancels in-flight manual sync and resets isSyncing to false`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 5.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey

        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } coAnswers {
            kotlinx.coroutines.delay(10_000L)
            SyncResult(0, 0, 0, 0, 0)
        }

        val job = viewModel.triggerManualSync()
        assertTrue(viewModel.isSyncing.value)

        viewModel.logout()

        assertTrue(job.isCancelled)
        assertFalse(viewModel.isSyncing.value)
        assertFalse(viewModel.isManualRefreshing.value)
    }

    @Test
    fun `createSnapshot calls snapshotSyncManager createAndUploadSnapshot`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 6.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockSnapshotSyncManager.createAndUploadSnapshot(vaultKey, SyncNamespace.GALLERY) } returns NetworkResult.Success(1L)

        viewModel.createSnapshot().join()

        coVerify(exactly = 1) { mockSnapshotSyncManager.createAndUploadSnapshot(vaultKey, SyncNamespace.GALLERY) }
    }

    @Test
    fun `90s ticker terminates cleanly when vault key is locked mid-cycle`() = runTest {
        val vaultKey = MasterVaultKey(ByteArray(32) { 7.toByte() })
        every { mockAuthRepository.getVaultKey() } returns vaultKey
        coEvery { mockCrdtSyncEngine.getPendingQueueCount("gallery") } returns 2
        coEvery { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) } returns SyncResult(0, 0, 0, 1, 2)

        val contentResolver: ContentResolver = mockk(relaxed = true)
        val uri: Uri = mockk(relaxed = true)
        every { uri.toString() } returns "content://media/ticker_lock_test"
        every { contentResolver.getType(uri) } returns "image/jpeg"

        coEvery { mockMediaUploadPipeline.uploadFromUri(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(200_000L)
            UploadResult.Success(
                MediaAsset(
                    fileId = "ticker-lock-file",
                    thumbnailHash = null,
                    mediaType = "image/jpeg",
                    fileSize = 100L,
                    capturedAt = 1000L
                ),
                isDeduplicated = false
            )
        }

        viewModel.enqueueUploads(contentResolver, listOf(uri))

        // First 90s ticker runs
        testDispatcher.scheduler.advanceTimeBy(95_000L)
        coVerify(atLeast = 1) { mockCrdtSyncEngine.sync(vaultKey, SyncNamespace.GALLERY) }

        // Vault is locked mid-cycle
        every { mockAuthRepository.getVaultKey() } returns null
        every { mockAuthRepository.isLoggedIn() } returns false

        // Advance another 95s
        testDispatcher.scheduler.advanceTimeBy(95_000L)

        // Sync should NOT be invoked with null/closed key
        coVerify(exactly = 1) { mockCrdtSyncEngine.sync(any(), any()) }

        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }
}
