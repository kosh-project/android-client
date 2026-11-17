package project.kosh.feature.gallery.ui

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.model.UploadStage

/**
 * Interface defining the thumbnail resolution and upload progress observation contract
 * for media assets displayed within [GalleryMediaGrid].
 *
 * Decoupling this contract from the concrete [project.kosh.feature.gallery.GalleryViewModel]
 * allows rendering previews in Android Studio and running isolated JVM unit tests without
 * mocking the full ViewModel dependency graph.
 */
interface GalleryThumbnailProvider {
    /**
     * Checks the in-memory LruCache for an already-decoded hardware [Bitmap].
     * Runs synchronously on the calling thread with zero allocations.
     */
    fun getFromMemoryCache(item: MediaWithCryptoDetails): Bitmap?

    /**
     * Returns true if a cached thumbnail sidecar exists on local disk storage.
     */
    fun hasDiskCache(fileId: String): Boolean

    /**
     * Returns true if a network fetch or disk decrypt is currently in flight for this asset.
     */
    fun isInFlight(fileId: String): Boolean

    /**
     * Asynchronously loads, decrypts, and decodes the thumbnail [Bitmap].
     */
    suspend fun loadThumbnail(item: MediaWithCryptoDetails): Bitmap?

    /**
     * Returns a reactive flow of upload progress stages for this specific asset.
     */
    fun getUploadStage(fileId: String): Flow<UploadStage?>
}

/**
 * Lightweight mock implementation of [GalleryThumbnailProvider] for Compose Previews
 * and test fixtures.
 */
object PreviewGalleryThumbnailProvider : GalleryThumbnailProvider {
    override fun getFromMemoryCache(item: MediaWithCryptoDetails): Bitmap? = null
    override fun hasDiskCache(fileId: String): Boolean = false
    override fun isInFlight(fileId: String): Boolean = false
    override suspend fun loadThumbnail(item: MediaWithCryptoDetails): Bitmap? = null
    override fun getUploadStage(fileId: String): Flow<UploadStage?> = flowOf(null)
}
