package project.kosh.feature.gallery.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncFolderPreferencesTest {

    private lateinit var context: Context
    private lateinit var preferences: SyncFolderPreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("kosh_sync_folders", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = SyncFolderPreferences(context)
    }

    @Test
    fun defaultPreferences_returnsEmptyBucketsAndIncompleteSetup() {
        assertTrue(preferences.getEnabledBucketIds().isEmpty())
        assertFalse(preferences.hasCompletedFolderSetup())
    }

    @Test
    fun setEnabledBucketIds_persistsSelectedBuckets() {
        val buckets = setOf("101", "202", "303")
        preferences.setEnabledBucketIds(buckets)

        assertEquals(buckets, preferences.getEnabledBucketIds())
    }

    @Test
    fun toggleBucket_addsAndRemovesBucketIdCorrectly() {
        preferences.setEnabledBucketIds(setOf("101"))

        preferences.toggleBucket("202", enable = true)
        assertEquals(setOf("101", "202"), preferences.getEnabledBucketIds())

        preferences.toggleBucket("101", enable = false)
        assertEquals(setOf("202"), preferences.getEnabledBucketIds())
    }

    @Test
    fun setFolderSetupCompleted_updatesFlagCorrectly() {
        assertFalse(preferences.hasCompletedFolderSetup())

        preferences.setFolderSetupCompleted(true)
        assertTrue(preferences.hasCompletedFolderSetup())

        preferences.setFolderSetupCompleted(false)
        assertFalse(preferences.hasCompletedFolderSetup())
    }
}
