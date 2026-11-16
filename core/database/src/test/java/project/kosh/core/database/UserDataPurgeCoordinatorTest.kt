package project.kosh.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import project.kosh.core.database.entity.MediaAssetEntity
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UserDataPurgeCoordinatorTest {

    private lateinit var context: Context
    private lateinit var database: KoshDatabase
    private lateinit var purgeCoordinator: UserDataPurgeCoordinator

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, KoshDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        purgeCoordinator = UserDataPurgeCoordinator(database, context)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun hasExistingUserData_returnsFalseWhenDatabaseEmpty() = runTest {
        assertFalse(purgeCoordinator.hasExistingUserData())
    }

    @Test
    fun hasExistingUserData_returnsTrueWhenMediaAssetExists() = runTest {
        val testAsset = MediaAssetEntity(
            fileId = "file-test-001",
            thumbnailHash = "thumb-hash-test",
            mediaType = "image/jpeg",
            width = 1920,
            height = 1080,
            fileSize = 1024L,
            capturedAt = 1700000000000L
        )
        database.mediaAssetDao().insert(testAsset)

        assertTrue(purgeCoordinator.hasExistingUserData())
    }

    @Test
    fun purgeAllUserData_clearsDatabaseTablesDeletesCachesAndNotifiesListeners() = runTest {
        // Setup initial database data
        val testAsset = MediaAssetEntity(
            fileId = "file-test-002",
            thumbnailHash = "thumb-hash-002",
            mediaType = "image/jpeg",
            width = 1920,
            height = 1080,
            fileSize = 2048L,
            capturedAt = 1700000000000L
        )
        database.mediaAssetDao().insert(testAsset)
        assertEquals(1, database.mediaAssetDao().getCount())

        // Setup cache files and directories
        val thumbDir = File(context.cacheDir, "kosh_thumbnails").apply {
            mkdirs()
            File(this, "thumb_abc.jpg").writeText("dummy-thumbnail-content")
        }
        val tempChunk = File(context.cacheDir, "temp_chunk_123.bin").apply {
            writeText("dummy-temp-chunk")
        }
        val thumbDl = File(context.cacheDir, "thumb_dl_456.tmp").apply {
            writeText("dummy-download-preview")
        }
        val unrelatedFile = File(context.cacheDir, "unrelated_app_file.txt").apply {
            writeText("keep-this-safe")
        }

        assertTrue(thumbDir.exists())
        assertTrue(tempChunk.exists())
        assertTrue(thumbDl.exists())
        assertTrue(unrelatedFile.exists())

        // Register purge listener
        var listenerInvokedCount = 0
        val listener = UserDataPurgeListener {
            listenerInvokedCount++
        }
        purgeCoordinator.registerListener(listener)

        // Execute purge
        purgeCoordinator.purgeAllUserData()

        // Verify database is completely cleared
        assertEquals(0, database.mediaAssetDao().getCount())
        assertFalse(purgeCoordinator.hasExistingUserData())

        // Verify thumbnail directory and temporary chunk files were deleted
        assertFalse(thumbDir.exists())
        assertFalse(tempChunk.exists())
        assertFalse(thumbDl.exists())

        // Verify unrelated cache files remain intact
        assertTrue(unrelatedFile.exists())

        // Verify listener was notified
        assertEquals(1, listenerInvokedCount)

        // Clean up unrelated file
        unrelatedFile.delete()
    }

    @Test
    fun unregisterListener_stopsReceivingPurgeCallbacks() = runTest {
        var listenerInvoked = false
        val listener = UserDataPurgeListener {
            listenerInvoked = true
        }

        purgeCoordinator.registerListener(listener)
        purgeCoordinator.unregisterListener(listener)

        purgeCoordinator.purgeAllUserData()

        assertFalse(listenerInvoked)
    }
}
