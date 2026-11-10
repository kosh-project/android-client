package project.kosh.feature.gallery.sync

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.MatrixCursor
import android.provider.MediaStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaFolderScannerTest {

    private lateinit var mockContext: Context
    private lateinit var mockResolver: ContentResolver
    private lateinit var mockPreferences: SyncFolderPreferences
    private lateinit var scanner: MediaFolderScanner

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockResolver = mockk(relaxed = true)
        mockPreferences = mockk(relaxed = true)
        every { mockContext.contentResolver } returns mockResolver
        scanner = MediaFolderScanner(mockContext, mockPreferences)
    }

    @Test
    fun queryMediaFolders_returnsEmptyWhenCursorIsEmpty() = runTest {
        val emptyCursor = MatrixCursor(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
        )
        every {
            mockResolver.query(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns emptyCursor

        val folders = scanner.queryMediaFolders()
        assertTrue(folders.isEmpty())
    }

    @Test
    fun queryMediaFolders_accumulatesUpTo3PreviewUrisAndSetsRepresentativeUri() = runTest {
        val cursor = MatrixCursor(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
        )
        // 4 items in descending date order
        cursor.addRow(arrayOf<Any?>(40L, "camera_bucket", "Camera", 4000L))
        cursor.addRow(arrayOf<Any?>(30L, "camera_bucket", "Camera", 3000L))
        cursor.addRow(arrayOf<Any?>(20L, "camera_bucket", "Camera", 2000L))
        cursor.addRow(arrayOf<Any?>(10L, "camera_bucket", "Camera", 1000L))

        every {
            mockResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        every {
            mockResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns null

        every { mockPreferences.getEnabledBucketIds() } returns setOf("camera_bucket")

        val folders = scanner.queryMediaFolders()
        assertEquals(1, folders.size)

        val cameraFolder = folders[0]
        assertEquals("camera_bucket", cameraFolder.bucketId)
        assertEquals("Camera", cameraFolder.displayName)
        assertEquals(4, cameraFolder.mediaCount)
        assertTrue(cameraFolder.isSelectedForSync)

        // Verifies up to 3 latest media URIs are accumulated
        assertEquals(3, cameraFolder.previewUris.size)
        val expectedFirstUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 40L)
        val expectedSecondUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 30L)
        val expectedThirdUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 20L)

        assertEquals(expectedFirstUri, cameraFolder.previewUris[0])
        assertEquals(expectedSecondUri, cameraFolder.previewUris[1])
        assertEquals(expectedThirdUri, cameraFolder.previewUris[2])

        // Representative URI is previewUris.firstOrNull()
        assertNotNull(cameraFolder.representativeUri)
        assertEquals(expectedFirstUri, cameraFolder.representativeUri)
    }

    @Test
    fun queryMediaFolders_prioritizesCameraOrDcimFolder() = runTest {
        val cursor = MatrixCursor(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
        )
        // Screenshots bucket with more items
        cursor.addRow(arrayOf<Any?>(1L, "screenshots_bucket", "Screenshots", 5000L))
        cursor.addRow(arrayOf<Any?>(2L, "screenshots_bucket", "Screenshots", 4000L))
        cursor.addRow(arrayOf<Any?>(3L, "screenshots_bucket", "Screenshots", 3000L))
        // Camera bucket with fewer items
        cursor.addRow(arrayOf<Any?>(4L, "camera_bucket", "Camera", 2000L))

        every {
            mockResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        every {
            mockResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns null

        val folders = scanner.queryMediaFolders()
        assertEquals(2, folders.size)
        assertEquals("Camera", folders[0].displayName)
        assertEquals("Screenshots", folders[1].displayName)
    }

    @Test
    fun queryMediaInFolder_returnsItemsSortedByDateModifiedDesc() = runTest {
        val cursor = MatrixCursor(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.BUCKET_ID
            )
        )
        cursor.addRow(arrayOf<Any?>(101L, "old.jpg", "image/jpeg", 1000L, 100L, "vacation_bucket"))
        cursor.addRow(arrayOf<Any?>(102L, "new.jpg", "image/jpeg", 2000L, 300L, "vacation_bucket"))

        every {
            mockResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        every {
            mockResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns null

        val items = scanner.queryMediaInFolder("vacation_bucket")
        assertEquals(2, items.size)
        assertEquals("new.jpg", items[0].displayName)
        assertEquals("old.jpg", items[1].displayName)
        assertTrue(items[0].dateModified > items[1].dateModified)
        assertEquals("vacation_bucket", items[0].bucketId)
    }

    @Test
    fun queryMediaFolders_accumulatesLatestPreviewsAcrossBothImagesAndVideos() = runTest {
        val imageCursor = MatrixCursor(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
        )
        // Older images
        imageCursor.addRow(arrayOf<Any?>(10L, "camera_bucket", "Camera", 1000L))
        imageCursor.addRow(arrayOf<Any?>(20L, "camera_bucket", "Camera", 2000L))

        val videoCursor = MatrixCursor(
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
            )
        )
        // Newer videos
        videoCursor.addRow(arrayOf<Any?>(30L, "camera_bucket", "Camera", 5000L))
        videoCursor.addRow(arrayOf<Any?>(40L, "camera_bucket", "Camera", 4000L))

        every {
            mockResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns imageCursor

        every {
            mockResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                any(),
                any(),
                any(),
                any()
            )
        } returns videoCursor

        every { mockPreferences.getEnabledBucketIds() } returns emptySet()

        val folders = scanner.queryMediaFolders()
        assertEquals(1, folders.size)

        val folder = folders[0]
        assertEquals(4, folder.mediaCount)
        assertEquals(3, folder.previewUris.size)

        // The newest item is video 30L (date 5000L)
        val expectedTopUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 30L)
        val expectedSecondUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 40L)
        val expectedThirdUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 20L)

        assertEquals(expectedTopUri, folder.previewUris[0])
        assertEquals(expectedSecondUri, folder.previewUris[1])
        assertEquals(expectedThirdUri, folder.previewUris[2])
        assertEquals(expectedTopUri, folder.representativeUri)
    }
}
