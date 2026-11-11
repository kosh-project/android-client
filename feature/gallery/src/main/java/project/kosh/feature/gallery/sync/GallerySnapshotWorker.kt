package project.kosh.feature.gallery.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import project.kosh.core.auth.AuthRepository
import project.kosh.core.database.crdt.SnapshotSyncManager
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.SyncNamespace
import project.kosh.core.network.ServerConfigurationStore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt EntryPoint providing access to domain dependencies for WorkManager workers.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GallerySnapshotWorkerEntryPoint {
    fun snapshotSyncManager(): SnapshotSyncManager
    fun authRepository(): AuthRepository
    fun serverConfigurationStore(): ServerConfigurationStore
}

/**
 * Background worker executing periodic encrypted gallery state snapshot creation and upload.
 */
class GallerySnapshotWorker @JvmOverloads constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val injectedSnapshotSyncManager: SnapshotSyncManager? = null,
    private val injectedAuthRepository: AuthRepository? = null
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val snapshotSyncManager = injectedSnapshotSyncManager ?: try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                GallerySnapshotWorkerEntryPoint::class.java
            ).snapshotSyncManager()
        } catch (e: Exception) {
            return@withContext Result.failure()
        }

        val authRepository = injectedAuthRepository ?: try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                GallerySnapshotWorkerEntryPoint::class.java
            ).authRepository()
        } catch (e: Exception) {
            return@withContext Result.failure()
        }

        val masterKey = authRepository.getVaultKey() ?: return@withContext Result.retry()

        when (val result = snapshotSyncManager.createAndUploadSnapshot(masterKey, SyncNamespace.GALLERY)) {
            is NetworkResult.Success -> Result.success()
            is NetworkResult.Failure -> Result.retry()
        }
    }
}

/**
 * Schedules periodic 7-day gallery state snapshot creation using Android WorkManager.
 */
@Singleton
class SnapshotWorkManagerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val snapshotSyncManager: SnapshotSyncManager,
    private val serverConfigStore: ServerConfigurationStore
) {
    companion object {
        const val UNIQUE_WORK_NAME = "kosh_gallery_periodic_snapshot"
    }

    internal var workManagerProvider: () -> WorkManager = { WorkManager.getInstance(context) }

    /**
     * Enqueues the 7-day periodic snapshot work request with charging and network constraints.
     */
    fun schedulePeriodicSnapshot() {
        val baseUrl = serverConfigStore.getBaseUrl()
        val networkType = if (snapshotSyncManager.isLocalNetworkEndpoint(baseUrl)) {
            NetworkType.CONNECTED
        } else {
            NetworkType.UNMETERED
        }

        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiredNetworkType(networkType)
            .build()

        val request = PeriodicWorkRequestBuilder<GallerySnapshotWorker>(7, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()

        workManagerProvider().enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Cancels any scheduled periodic snapshot work.
     */
    fun cancelPeriodicSnapshot() {
        workManagerProvider().cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
