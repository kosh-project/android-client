package project.kosh.feature.gallery.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import project.kosh.core.auth.AuthRepository
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.database.crdt.SnapshotSyncManager
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.SyncNamespace
import project.kosh.core.network.ServerConfigurationStore

@RunWith(RobolectricTestRunner::class)
class GallerySnapshotWorkerTest {

    private lateinit var context: Context
    private lateinit var mockSnapshotSyncManager: SnapshotSyncManager
    private lateinit var mockAuthRepository: AuthRepository
    private lateinit var mockServerConfigStore: ServerConfigurationStore
    private lateinit var mockWorkManager: WorkManager
    private lateinit var workerParams: WorkerParameters
    private val masterVaultKey = MasterVaultKey(ByteArray(32) { 8.toByte() })

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockSnapshotSyncManager = mockk(relaxed = true)
        mockAuthRepository = mockk(relaxed = true)
        mockServerConfigStore = mockk(relaxed = true)
        mockWorkManager = mockk(relaxed = true)
        workerParams = mockk(relaxed = true)
    }

    @Test
    fun testGallerySnapshotWorker_success() = runBlocking {
        every { mockAuthRepository.getVaultKey() } returns masterVaultKey
        coEvery {
            mockSnapshotSyncManager.createAndUploadSnapshot(masterVaultKey, SyncNamespace.GALLERY)
        } returns NetworkResult.Success(10L)

        val worker = GallerySnapshotWorker(
            appContext = context,
            workerParams = workerParams,
            injectedSnapshotSyncManager = mockSnapshotSyncManager,
            injectedAuthRepository = mockAuthRepository
        )

        val result = worker.doWork()
        assertEquals(Result.success(), result)
    }

    @Test
    fun testGallerySnapshotWorker_noVaultKey_retries() = runBlocking {
        every { mockAuthRepository.getVaultKey() } returns null

        val worker = GallerySnapshotWorker(
            appContext = context,
            workerParams = workerParams,
            injectedSnapshotSyncManager = mockSnapshotSyncManager,
            injectedAuthRepository = mockAuthRepository
        )

        val result = worker.doWork()
        assertEquals(Result.retry(), result)
    }

    @Test
    fun testGallerySnapshotWorker_failure_retries() = runBlocking {
        every { mockAuthRepository.getVaultKey() } returns masterVaultKey
        coEvery {
            mockSnapshotSyncManager.createAndUploadSnapshot(masterVaultKey, SyncNamespace.GALLERY)
        } returns NetworkResult.Connectivity.NetworkUnavailable()

        val worker = GallerySnapshotWorker(
            appContext = context,
            workerParams = workerParams,
            injectedSnapshotSyncManager = mockSnapshotSyncManager,
            injectedAuthRepository = mockAuthRepository
        )

        val result = worker.doWork()
        assertEquals(Result.retry(), result)
    }

    @Test
    fun testSnapshotWorkManagerScheduler_localNetwork_usesConnectedNetworkType() {
        every { mockServerConfigStore.getBaseUrl() } returns "http://192.168.1.100:6969"
        every { mockSnapshotSyncManager.isLocalNetworkEndpoint("http://192.168.1.100:6969") } returns true

        val scheduler = SnapshotWorkManagerScheduler(
            context = context,
            snapshotSyncManager = mockSnapshotSyncManager,
            serverConfigStore = mockServerConfigStore
        ).apply {
            workManagerProvider = { mockWorkManager }
        }

        val requestSlot = slot<PeriodicWorkRequest>()
        every {
            mockWorkManager.enqueueUniquePeriodicWork(
                SnapshotWorkManagerScheduler.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                capture(requestSlot)
            )
        } returns mockk(relaxed = true)

        scheduler.schedulePeriodicSnapshot()

        verify(exactly = 1) {
            mockWorkManager.enqueueUniquePeriodicWork(
                SnapshotWorkManagerScheduler.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                any()
            )
        }

        val workSpec = requestSlot.captured.workSpec
        assertEquals(NetworkType.CONNECTED, workSpec.constraints.requiredNetworkType)
        assertTrue(workSpec.constraints.requiresCharging())
    }

    @Test
    fun testSnapshotWorkManagerScheduler_remoteNetwork_usesUnmeteredNetworkType() {
        every { mockServerConfigStore.getBaseUrl() } returns "https://api.koshvault.io"
        every { mockSnapshotSyncManager.isLocalNetworkEndpoint("https://api.koshvault.io") } returns false

        val scheduler = SnapshotWorkManagerScheduler(
            context = context,
            snapshotSyncManager = mockSnapshotSyncManager,
            serverConfigStore = mockServerConfigStore
        ).apply {
            workManagerProvider = { mockWorkManager }
        }

        val requestSlot = slot<PeriodicWorkRequest>()
        every {
            mockWorkManager.enqueueUniquePeriodicWork(
                SnapshotWorkManagerScheduler.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                capture(requestSlot)
            )
        } returns mockk(relaxed = true)

        scheduler.schedulePeriodicSnapshot()

        val workSpec = requestSlot.captured.workSpec
        assertEquals(NetworkType.UNMETERED, workSpec.constraints.requiredNetworkType)
        assertTrue(workSpec.constraints.requiresCharging())
    }

    @Test
    fun testGallerySnapshotWorker_twoArgConstructorReflection() {
        val constructor = GallerySnapshotWorker::class.java.getDeclaredConstructor(
            Context::class.java,
            WorkerParameters::class.java
        )
        org.junit.Assert.assertNotNull(constructor)
        val worker = constructor.newInstance(context, workerParams)
        org.junit.Assert.assertNotNull(worker)
    }

    @Test
    fun testSnapshotWorkManagerScheduler_cancelPeriodicSnapshot() {
        val scheduler = SnapshotWorkManagerScheduler(
            context = context,
            snapshotSyncManager = mockSnapshotSyncManager,
            serverConfigStore = mockServerConfigStore
        ).apply {
            workManagerProvider = { mockWorkManager }
        }

        every {
            mockWorkManager.cancelUniqueWork(SnapshotWorkManagerScheduler.UNIQUE_WORK_NAME)
        } returns mockk(relaxed = true)

        scheduler.cancelPeriodicSnapshot()

        verify(exactly = 1) {
            mockWorkManager.cancelUniqueWork(SnapshotWorkManagerScheduler.UNIQUE_WORK_NAME)
        }
    }
}
