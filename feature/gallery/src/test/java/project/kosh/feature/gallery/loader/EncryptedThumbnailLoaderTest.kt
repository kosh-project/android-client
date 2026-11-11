package project.kosh.feature.gallery.loader

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import project.kosh.core.crypto.AesGcmEnvelope
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.AssetClient
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EncryptedThumbnailLoaderTest {

    private lateinit var context: Context
    private lateinit var mockAssetClient: AssetClient
    private lateinit var loader: EncryptedThumbnailLoader

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockAssetClient = mockk(relaxed = true)
        loader = EncryptedThumbnailLoader(context, mockAssetClient)
        loader.diskCacheDir.deleteRecursively()
        loader.diskCacheDir.mkdirs()
    }

    @After
    fun teardown() {
        loader.clearMemoryCache()
        loader.diskCacheDir.deleteRecursively()
    }

    @Test
    fun loadThumbnail_returnsFromMemoryCacheDirectly() = runTest {
        val fileId = "test-mem-1"
        val existingBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        loader.memoryCache.put(fileId, existingBitmap)

        val item = MediaWithCryptoDetails(
            asset = MediaAssetEntity(
                fileId = fileId,
                mediaType = "image/jpeg",
                fileSize = 1000L,
                capturedAt = 1000L
            ),
            cryptoMapping = null
        )

        val result = loader.loadThumbnail(item, masterKey = null)

        assertNotNull(result)
        assertEquals(existingBitmap, result)
        coVerify(exactly = 0) { mockAssetClient.downloadAsset(any(), any()) }
    }

    @Test
    fun loadThumbnail_returnsFromDiskCacheAndPopulatesMemoryCache() = runTest {
        val fileId = "test-disk-1"
        val testBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        testBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val pngBytes = stream.toByteArray()

        val diskFile = File(loader.diskCacheDir, "$fileId.webp")
        diskFile.writeBytes(pngBytes)

        val item = MediaWithCryptoDetails(
            asset = MediaAssetEntity(
                fileId = fileId,
                mediaType = "image/jpeg",
                fileSize = 1000L,
                capturedAt = 1000L
            ),
            cryptoMapping = null
        )

        val result = loader.loadThumbnail(item, masterKey = null)

        assertNotNull(result)
        assertEquals(result, loader.memoryCache.get(fileId))
        coVerify(exactly = 0) { mockAssetClient.downloadAsset(any(), any()) }
    }

    @Test
    fun loadThumbnail_downloadsAndDecryptsFromCasFallback() = runTest {
        val fileId = "test-cas-1"
        val testBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        testBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val plainBytes = stream.toByteArray()

        val dek = ByteArray(32) { (it + 5).toByte() }
        val masterKey = MasterVaultKey(ByteArray(32) { (it + 20).toByte() })

        val encryptedBytes = AesGcmEnvelope.encrypt(plainBytes, dek)
        val wrappedDekHex = AesGcmEnvelope.wrapKey(dek, masterKey.keyBytes)
        val thumbHash = "blake3-cas-thumbnail-hash"

        coEvery { mockAssetClient.downloadAsset(thumbHash, any()) } answers {
            val destFile = secondArg<File>()
            destFile.writeBytes(encryptedBytes)
            NetworkResult.Success(destFile)
        }

        val item = MediaWithCryptoDetails(
            asset = MediaAssetEntity(
                fileId = fileId,
                mediaType = "image/jpeg",
                fileSize = plainBytes.size.toLong(),
                capturedAt = 1000L
            ),
            cryptoMapping = MediaCryptoMappingEntity(
                fileId = fileId,
                plaintextSha256 = "dummy-sha256",
                thumbnailCiphertextHash = thumbHash,
                wrappedDek = wrappedDekHex,
                wrappedThumbnailDek = wrappedDekHex
            )
        )

        val result = loader.loadThumbnail(item, masterKey)

        assertNotNull(result)
        assertEquals(result, loader.memoryCache.get(fileId))

        val diskFile = File(loader.diskCacheDir, "$fileId.webp")
        assertTrue(diskFile.exists())
        assertEquals(plainBytes.size, diskFile.readBytes().size)

        coVerify(exactly = 1) { mockAssetClient.downloadAsset(thumbHash, any()) }
    }

    @Test
    fun loadThumbnail_returnsNullWhenMasterKeyIsNull() = runTest {
        val fileId = "test-locked-key"
        val item = MediaWithCryptoDetails(
            asset = MediaAssetEntity(
                fileId = fileId,
                mediaType = "image/jpeg",
                fileSize = 500L,
                capturedAt = 1000L
            ),
            cryptoMapping = MediaCryptoMappingEntity(
                fileId = fileId,
                plaintextSha256 = "dummy",
                thumbnailCiphertextHash = "some-hash",
                wrappedDek = "wrapped"
            )
        )

        val result = loader.loadThumbnail(item, masterKey = null)
        assertNull(result)
        coVerify(exactly = 0) { mockAssetClient.downloadAsset(any(), any()) }
    }

    @Test
    fun loadThumbnail_returnsNullWhenDownloadFails() = runTest {
        val fileId = "test-dl-fail"
        val masterKey = MasterVaultKey(ByteArray(32) { 1 })
        val dek = ByteArray(32) { 2 }
        val wrappedDekHex = AesGcmEnvelope.wrapKey(dek, masterKey.keyBytes)

        coEvery { mockAssetClient.downloadAsset("bad-hash", any()) } returns
            NetworkResult.Unknown("Network disconnected", RuntimeException("Timeout"))

        val item = MediaWithCryptoDetails(
            asset = MediaAssetEntity(
                fileId = fileId,
                mediaType = "image/jpeg",
                fileSize = 500L,
                capturedAt = 1000L
            ),
            cryptoMapping = MediaCryptoMappingEntity(
                fileId = fileId,
                plaintextSha256 = "dummy",
                thumbnailCiphertextHash = "bad-hash",
                wrappedDek = wrappedDekHex
            )
        )

        val result = loader.loadThumbnail(item, masterKey)
        assertNull(result)
    }

    @Test
    fun clearMemoryCache_evictsCachedBitmaps() {
        val fileId = "test-evict"
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        loader.memoryCache.put(fileId, bitmap)

        assertEquals(bitmap, loader.memoryCache.get(fileId))
        loader.clearMemoryCache()
        assertNull(loader.memoryCache.get(fileId))
    }

    @Test
    fun clearDiskCache_removesAllCachedFiles() {
        val f1 = File(loader.diskCacheDir, "test1.webp").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val f2 = File(loader.diskCacheDir, "test2.webp").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        assertTrue(f1.exists())
        assertTrue(f2.exists())

        loader.clearDiskCache()
        assertFalse(f1.exists())
        assertFalse(f2.exists())
    }

    @Test
    fun loadLocalThumbnail_returnsBitmapAndPopulatesMemoryCache() {
        val testBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        testBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()

        val tempFile = File(context.cacheDir, "local_test.png").apply { writeBytes(bytes) }
        val uri = android.net.Uri.fromFile(tempFile)

        val result = loader.loadLocalThumbnail(context.contentResolver, uri, "local-123")
        assertNotNull(result)
        assertEquals(result, loader.memoryCache.get("local-123"))

        tempFile.delete()
    }

    @Test
    fun loadLocalThumbnail_subSamplesLargeBitmaps() {
        // Create 1024x1024 bitmap
        val largeBitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        largeBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()

        val tempFile = File(context.cacheDir, "large_local_test.png").apply { writeBytes(bytes) }
        val uri = android.net.Uri.fromFile(tempFile)

        val result = loader.loadLocalThumbnail(context.contentResolver, uri, "large-123")
        assertNotNull(result)
        // With targetSize = 256, inSampleSize is 4 for 1024px, resulting in <= 256px
        assertTrue(result!!.width <= 512)
        assertTrue(result.height <= 512)
        assertEquals(result, loader.memoryCache.get("large-123"))

        tempFile.delete()
    }

    @Test
    fun loadLocalUriPreview_cachesKeyedByUriString() {
        val testBitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        testBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()

        val tempFile = File(context.cacheDir, "uri_preview_test.png").apply { writeBytes(bytes) }
        val uri = android.net.Uri.fromFile(tempFile)

        val result = loader.loadLocalUriPreview(context.contentResolver, uri, targetSize = 180)
        assertNotNull(result)
        assertEquals(result, loader.memoryCache.get(uri.toString()))

        // Subsequent call returns cached bitmap directly without re-reading
        val cachedResult = loader.loadLocalUriPreview(context.contentResolver, uri, targetSize = 180)
        assertEquals(result, cachedResult)

        tempFile.delete()
    }

    @Test
    fun loadLocalThumbnail_videoMimeType_checksMemoryCacheFirst() {
        val fileId = "video-file-1"
        val cachedFrame = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        loader.memoryCache.put(fileId, cachedFrame)

        val uri = android.net.Uri.parse("content://media/external/video/media/999")
        val result = loader.loadLocalThumbnail(
            contentResolver = context.contentResolver,
            uri = uri,
            fileId = fileId,
            mimeType = "video/mp4"
        )

        assertNotNull(result)
        assertEquals(cachedFrame, result)
    }

    @Test
    fun loadLocalThumbnail_videoMimeType_handlesMissingGracefully() {
        val uri = android.net.Uri.parse("content://media/external/video/media/invalid_id")
        val result = loader.loadLocalThumbnail(
            contentResolver = context.contentResolver,
            uri = uri,
            fileId = "video-invalid",
            mimeType = "video/mp4"
        )
        assertNull(result)
    }

    @Test
    fun loadLocalUriPreview_videoMimeType_cachesDecodedFrame() {
        val uri = android.net.Uri.parse("content://media/external/video/media/cached_vid")
        val cachedFrame = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        loader.memoryCache.put(uri.toString(), cachedFrame)

        val result = loader.loadLocalUriPreview(
            contentResolver = context.contentResolver,
            uri = uri,
            targetSize = 180,
            mimeType = "video/mp4"
        )

        assertNotNull(result)
        assertEquals(cachedFrame, result)
    }

    @Test
    fun loadLocalThumbnail_nullFileId_cachesByUriString() {
        val uri = android.net.Uri.parse("content://media/external/video/media/null_file_id_vid")
        val cachedFrame = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        loader.memoryCache.put(uri.toString(), cachedFrame)

        val result = loader.loadLocalThumbnail(
            contentResolver = context.contentResolver,
            uri = uri,
            fileId = null,
            mimeType = "video/mp4"
        )

        assertNotNull(result)
        assertEquals(cachedFrame, result)
    }

    @Test
    fun loadThumbnail_retriesOnRateLimitedAndSucceeds() = runTest {
        val fileId = "test-rate-limit-retry"
        val testBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        testBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val plainBytes = stream.toByteArray()

        val dek = ByteArray(32) { (it + 5).toByte() }
        val masterKey = MasterVaultKey(ByteArray(32) { (it + 20).toByte() })

        val encryptedBytes = AesGcmEnvelope.encrypt(plainBytes, dek)
        val wrappedDekHex = AesGcmEnvelope.wrapKey(dek, masterKey.keyBytes)
        val thumbHash = "blake3-cas-rate-limit-hash"

        var callCount = 0
        coEvery { mockAssetClient.downloadAsset(thumbHash, any()) } answers {
            callCount++
            if (callCount == 1) {
                NetworkResult.Http.RateLimited(retryAfterSeconds = null)
            } else {
                val destFile = secondArg<File>()
                destFile.writeBytes(encryptedBytes)
                NetworkResult.Success(destFile)
            }
        }

        val item = MediaWithCryptoDetails(
            asset = MediaAssetEntity(
                fileId = fileId,
                mediaType = "image/jpeg",
                fileSize = plainBytes.size.toLong(),
                capturedAt = 1000L
            ),
            cryptoMapping = MediaCryptoMappingEntity(
                fileId = fileId,
                plaintextSha256 = "dummy-sha256",
                thumbnailCiphertextHash = thumbHash,
                wrappedDek = wrappedDekHex,
                wrappedThumbnailDek = wrappedDekHex
            )
        )

        val result = loader.loadThumbnail(item, masterKey)

        assertNotNull(result)
        assertEquals(2, callCount)
    }
}

