package project.kosh.feature.gallery.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import project.kosh.feature.gallery.model.DiscoveredMediaItem
import project.kosh.feature.gallery.model.MediaFolder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-performance MediaStore indexer that scans device photos and videos grouped by album / folder.
 */
@Singleton
class MediaFolderScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: SyncFolderPreferences
) {

    /**
     * Queries MediaStore for all on-device photo and video folders, computing item counts
     * and attaching the latest representative thumbnail URI.
     */
    suspend fun queryMediaFolders(): List<MediaFolder> = withContext(Dispatchers.IO) {
        val enabledBuckets = preferences.getEnabledBucketIds()
        val folderMap = mutableMapOf<String, FolderAccumulator>()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val urisToQuery = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )

        for (contentUri in urisToQuery) {
            val cursorCountPerBucket = mutableMapOf<String, Int>()
            try {
                context.contentResolver.query(
                    contentUri,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val bucketIdCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                    val bucketNameCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = if (idCol != -1) cursor.getLong(idCol) else continue
                        val rawBucketId = if (bucketIdCol != -1) cursor.getString(bucketIdCol) else null
                        val rawBucketName = if (bucketNameCol != -1) cursor.getString(bucketNameCol) else null
                        val date = if (dateCol != -1) cursor.getLong(dateCol) * 1000L else System.currentTimeMillis()

                        val bucketId = rawBucketId ?: "unknown"
                        val bucketName = rawBucketName ?: "Media"

                        val itemUri = ContentUris.withAppendedId(contentUri, id)

                        val acc = folderMap.getOrPut(bucketId) {
                            FolderAccumulator(
                                bucketId = bucketId,
                                displayName = bucketName
                            )
                        }
                        val itemsFromCurrentCursor = cursorCountPerBucket.getOrDefault(bucketId, 0)
                        if (itemsFromCurrentCursor < 3) {
                            acc.previewCandidates.add(itemUri to date)
                            cursorCountPerBucket[bucketId] = itemsFromCurrentCursor + 1
                        }
                        acc.count++
                    }
                }
            } catch (_: Exception) {
                // Return gracefully if permission revoked or provider unavailable
            }
        }

        folderMap.values
            .map { acc ->
                val previews = acc.previewUris
                MediaFolder(
                    bucketId = acc.bucketId,
                    displayName = acc.displayName,
                    mediaCount = acc.count,
                    representativeUri = previews.firstOrNull(),
                    previewUris = previews,
                    isSelectedForSync = enabledBuckets.contains(acc.bucketId)
                )
            }
            .sortedWith(
                compareByDescending<MediaFolder> { it.displayName.equals("Camera", ignoreCase = true) || it.displayName.equals("DCIM", ignoreCase = true) }
                    .thenByDescending { it.mediaCount }
            )
    }

    /**
     * Queries all media items within a specified MediaStore bucket ordered by [MediaStore.MediaColumns.DATE_MODIFIED] DESC.
     */
    suspend fun queryMediaInFolder(bucketId: String): List<DiscoveredMediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<DiscoveredMediaItem>()

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.BUCKET_ID
        )

        val selection = "${MediaStore.MediaColumns.BUCKET_ID} = ?"
        val selectionArgs = arrayOf(bucketId)
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        val urisToQuery = listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )

        for (contentUri in urisToQuery) {
            try {
                context.contentResolver.query(
                    contentUri,
                    projection,
                    selection,
                    selectionArgs,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = if (idCol != -1) cursor.getLong(idCol) else continue
                        val name = if (nameCol != -1) cursor.getString(nameCol) ?: "media_$id" else "media_$id"
                        val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "image/jpeg" else "image/jpeg"
                        val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                        val date = if (dateCol != -1) cursor.getLong(dateCol) * 1000L else System.currentTimeMillis()

                        val itemUri = ContentUris.withAppendedId(contentUri, id)

                        items.add(
                            DiscoveredMediaItem(
                                id = id,
                                uri = itemUri,
                                displayName = name,
                                mimeType = mime,
                                size = size,
                                dateModified = date,
                                bucketId = bucketId
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // Ignore query exceptions
            }
        }

        items.sortedByDescending { it.dateModified }
    }

    private class FolderAccumulator(
        val bucketId: String,
        val displayName: String,
        val previewCandidates: MutableList<Pair<Uri, Long>> = mutableListOf(),
        var count: Int = 0
    ) {
        val previewUris: List<Uri>
            get() = previewCandidates.sortedByDescending { it.second }.take(3).map { it.first }
    }
}
