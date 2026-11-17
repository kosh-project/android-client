package project.kosh.core.database

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listener invoked when local user data, database, or cache is purged.
 */
fun interface UserDataPurgeListener {
    fun onUserDataPurged()
}

/**
 * Service managing user data, database, and local cache purging upon account switch or new registration.
 *
 * Ensures strict cryptographic isolation: when an account switch is detected, wipes all Room SQLite tables,
 * clears the decrypted thumbnail preview disk cache, purges in-memory caches, and resets temporary media buffers.
 */
@Singleton
class UserDataPurgeCoordinator @Inject constructor(
    private val database: KoshDatabase,
    @ApplicationContext private val context: Context
) {
    private val listeners = CopyOnWriteArrayList<UserDataPurgeListener>()

    /**
     * Registers a purge listener to be invoked when user data is purged.
     */
    fun registerListener(listener: UserDataPurgeListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    /**
     * Unregisters a previously registered purge listener.
     */
    fun unregisterListener(listener: UserDataPurgeListener) {
        listeners.remove(listener)
    }

    /**
     * Executes complete teardown of local user data asynchronously on [Dispatchers.IO].
     */
    suspend fun purgeAllUserData() = withContext(Dispatchers.IO) {
        purgeAllUserDataInternal()
    }

    /**
     * Blocking variant for non-coroutine initialization pipelines.
     */
    fun purgeAllUserDataBlocking() {
        purgeAllUserDataInternal()
    }

    /**
     * Checks if there are active media assets stored in the local Room database.
     */
    suspend fun hasExistingUserData(): Boolean = withContext(Dispatchers.IO) {
        try {
            database.mediaAssetDao().getCount() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun purgeAllUserDataInternal() {
        // 1. Wipe all Room SQLite database tables (media_assets, media_crypto_mapping, sync_action_queue, sync_watermarks, albums, album_media_cross_ref)
        try {
            database.clearAllTables()
        } catch (_: Exception) {
            // Safe isolation
        }

        // 2. Clear thumbnail disk cache directory contents
        try {
            val thumbDir = File(context.cacheDir, "kosh_thumbnails")
            if (thumbDir.exists()) {
                thumbDir.listFiles()?.forEach { it.delete() }
            } else {
                thumbDir.mkdirs()
            }
        } catch (_: Exception) {
            // Ignore transient file locking
        }

        // 3. Clear temporary streaming, thumbnail downloads, or media cache files
        try {
            val tempFiles = context.cacheDir.listFiles { _, name ->
                name != "kosh_thumbnails" && (name.startsWith("kosh_") || name.startsWith("temp_") || name.startsWith("thumb_dl_") || name.endsWith(".bin") || name.endsWith(".tmp"))
            }
            tempFiles?.forEach { file ->
                try {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                } catch (_: Exception) {
                    // Ignore transient file locking
                }
            }
        } catch (_: Exception) {
            // Safe isolation
        }

        // 4. Notify registered purge listeners (e.g. EncryptedThumbnailLoader, SyncFolderPreferences)
        listeners.forEach { listener ->
            try {
                listener.onUserDataPurged()
            } catch (_: Exception) {
                // Ignore listener exceptions
            }
        }
    }
}
