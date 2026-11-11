package project.kosh.feature.gallery.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent preferences tracking enabled media sync buckets and first-launch onboarding state.
 */
@Singleton
class SyncFolderPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("kosh_sync_folders", Context.MODE_PRIVATE)

    /**
     * Returns the set of MediaStore bucket IDs selected by the user for automatic vault sync.
     */
    fun getEnabledBucketIds(): Set<String> =
        prefs.getStringSet(KEY_ENABLED_BUCKETS, emptySet())?.toSet() ?: emptySet()

    /**
     * Persists the set of MediaStore bucket IDs selected for automatic vault sync.
     */
    fun setEnabledBucketIds(bucketIds: Set<String>) {
        prefs.edit().putStringSet(KEY_ENABLED_BUCKETS, bucketIds).apply()
    }

    /**
     * Returns true if the user has already completed the first-time permission and folder setup prompt.
     */
    fun hasCompletedFolderSetup(): Boolean =
        prefs.getBoolean(KEY_SETUP_COMPLETED, false)

    /**
     * Flags that the user has completed or dismissed the initial folder setup sheet.
     */
    fun setFolderSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
    }

    /**
     * Toggles sync enrollment for a specific bucket ID.
     */
    fun toggleBucket(bucketId: String, enable: Boolean) {
        val current = getEnabledBucketIds().toMutableSet()
        if (enable) current.add(bucketId) else current.remove(bucketId)
        setEnabledBucketIds(current)
    }

    companion object {
        private const val KEY_ENABLED_BUCKETS = "enabled_bucket_ids"
        private const val KEY_SETUP_COMPLETED = "has_completed_folder_setup"
    }
}
