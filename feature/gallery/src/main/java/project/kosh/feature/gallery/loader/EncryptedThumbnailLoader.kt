package project.kosh.feature.gallery.loader

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import project.kosh.core.crypto.AesGcmEnvelope
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.database.model.MediaWithCryptoDetails
import project.kosh.core.model.NetworkResult
import project.kosh.core.network.AssetClient
import java.io.File
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zero-Knowledge image caching and on-demand AES-256-GCM decryption engine for gallery previews.
 *
 * Implements a 2-tier caching hierarchy:
 * 1. L1 Memory Cache: High-speed Android [LruCache] (capped at 32MB) for instantaneous 120 FPS fling rendering.
 * 2. L2 Disk Cache: Plaintext decrypted WebP previews persisted in application cache directory.
 * 3. Remote Fallback: Downloads encrypted CAS thumbnail blobs on demand,
 *    unwraps the per-file DEK with the active [MasterVaultKey], and decrypts the preview in memory.
 */
@Singleton
class EncryptedThumbnailLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val assetClient: AssetClient,
    private val purgeCoordinator: project.kosh.core.database.UserDataPurgeCoordinator? = null
) {
    private val maxMemoryBytes = 32 * 1024 * 1024 // 32 MB
    internal val memoryCache = object : LruCache<String, Bitmap>(maxMemoryBytes) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            val bytes = try {
                bitmap.allocationByteCount
            } catch (_: Exception) {
                bitmap.byteCount
            }
            return if (bytes > 0) bytes else 512 * 512 * 4
        }
    }

    internal val diskCacheDir = File(context.cacheDir, "kosh_thumbnails").apply { mkdirs() }
    private val downloadSemaphore = Semaphore(4)
    private val decodeSemaphore = Semaphore(4)

    init {
        purgeCoordinator?.registerListener {
            clearMemoryCache()
            clearDiskCache()
        }
    }

    fun getFromMemoryCache(key: String): Bitmap? = memoryCache.get(key)

    fun hasDiskCache(fileId: String): Boolean {
        val diskFile = File(diskCacheDir, "$fileId.webp")
        return diskFile.exists() && diskFile.length() > 0
    }

    fun cacheDecryptedThumbnail(fileId: String, plaintextBytes: ByteArray, targetPx: Int = 256): Bitmap? {
        val diskFile = File(diskCacheDir, "$fileId.webp")
        if (!diskFile.exists() || diskFile.length() == 0L) {
            try {
                diskFile.writeBytes(plaintextBytes)
            } catch (_: Exception) {}
        }
        val bitmap = decodeHardwareBitmap(plaintextBytes, targetPx) ?: decodeHardwareBitmap(diskFile, targetPx)
        if (bitmap != null) {
            memoryCache.put(fileId, bitmap)
        }
        return bitmap
    }

    private fun configureHardwareDownsampleAndCenterCrop(
        decoder: ImageDecoder,
        info: ImageDecoder.ImageInfo,
        targetPx: Int
    ) {
        decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
        val srcWidth = info.size.width
        val srcHeight = info.size.height

        if (srcWidth > targetPx && srcHeight > targetPx) {
            val scale = targetPx.toFloat() / minOf(srcWidth, srcHeight)
            val scaledW = (srcWidth * scale).toInt().coerceAtLeast(targetPx)
            val scaledH = (srcHeight * scale).toInt().coerceAtLeast(targetPx)
            decoder.setTargetSize(scaledW, scaledH)

            val cropLeft = (scaledW - targetPx) / 2
            val cropTop = (scaledH - targetPx) / 2
            decoder.crop = Rect(
                cropLeft,
                cropTop,
                cropLeft + targetPx,
                cropTop + targetPx
            )
        }
    }

    internal fun decodeHardwareBitmap(file: File, targetPx: Int = 256): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(file)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    configureHardwareDownsampleAndCenterCrop(decoder, info, targetPx)
                }
            } catch (_: Throwable) {
                // Fallback to BitmapFactory
            }
        }
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            var sampleSize = 1
            if (boundsOptions.outWidth > targetPx && boundsOptions.outHeight > targetPx) {
                val minOrig = minOf(boundsOptions.outWidth, boundsOptions.outHeight)
                while (minOrig / (sampleSize * 2) >= targetPx) {
                    sampleSize *= 2
                }
            }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.HARDWARE
                inSampleSize = sampleSize
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
                ?: BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (_: Exception) {
                null
            }
        }
    }

    internal fun decodeHardwareBitmap(bytes: ByteArray, targetPx: Int = 256): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val buffer = ByteBuffer.wrap(bytes)
                val source = ImageDecoder.createSource(buffer)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    configureHardwareDownsampleAndCenterCrop(decoder, info, targetPx)
                }
            } catch (_: Throwable) {
                // Fallback to BitmapFactory
            }
        }
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
            var sampleSize = 1
            if (boundsOptions.outWidth > targetPx && boundsOptions.outHeight > targetPx) {
                val minOrig = minOf(boundsOptions.outWidth, boundsOptions.outHeight)
                while (minOrig / (sampleSize * 2) >= targetPx) {
                    sampleSize *= 2
                }
            }
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.HARDWARE
                inSampleSize = sampleSize
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun calculateInSampleSize(
        contentResolver: ContentResolver,
        uri: Uri,
        targetSize: Int
    ): Int {
        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return 1

            var inSampleSize = 1
            val maxOriginal = maxOf(origWidth, origHeight)
            while (maxOriginal / (inSampleSize * 2) >= targetSize) {
                inSampleSize *= 2
            }
            inSampleSize
        } catch (_: Exception) {
            1
        }
    }

    /**
     * Decodes and caches a local preview bitmap for an in-flight upload item.
     */
    fun loadLocalThumbnail(
        contentResolver: ContentResolver,
        uri: Uri,
        fileId: String? = null,
        mimeType: String? = null,
        targetPx: Int = 256
    ): Bitmap? {
        val cacheKey = fileId ?: uri.toString()
        memoryCache.get(cacheKey)?.let { return it }

        val isVideo = (mimeType ?: contentResolver.getType(uri))?.startsWith("video/", ignoreCase = true) == true
        val bitmap = if (isVideo) {
            extractVideoThumbnail(contentResolver, uri, targetSize = targetPx)
        } else {
            try {
                val sampleSize = calculateInSampleSize(contentResolver, uri, targetSize = targetPx)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.HARDWARE
                }
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: contentResolver.openInputStream(uri)?.use { stream ->
                    val fallbackOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    BitmapFactory.decodeStream(stream, null, fallbackOptions)
                }
            } catch (_: Exception) {
                null
            }
        }

        if (bitmap != null) {
            memoryCache.put(cacheKey, bitmap)
        }
        return bitmap
    }

    /**
     * Decodes and caches a local preview bitmap keyed by URI string with sub-sampling.
     */
    fun loadLocalUriPreview(
        contentResolver: ContentResolver,
        uri: Uri,
        targetSize: Int = 180,
        mimeType: String? = null
    ): Bitmap? {
        val cacheKey = uri.toString()
        memoryCache.get(cacheKey)?.let { return it }

        val isVideo = (mimeType ?: contentResolver.getType(uri))?.startsWith("video/", ignoreCase = true) == true
        val bitmap = if (isVideo) {
            extractVideoThumbnail(contentResolver, uri, targetSize)
        } else {
            try {
                val sampleSize = calculateInSampleSize(contentResolver, uri, targetSize)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.HARDWARE
                }
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: contentResolver.openInputStream(uri)?.use { stream ->
                    val fallbackOptions = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                    }
                    BitmapFactory.decodeStream(stream, null, fallbackOptions)
                }
            } catch (_: Exception) {
                null
            }
        }

        if (bitmap != null) {
            memoryCache.put(cacheKey, bitmap)
        }
        return bitmap
    }

    private fun extractVideoThumbnail(
        contentResolver: ContentResolver,
        uri: Uri,
        targetSize: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return contentResolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
            } catch (_: Exception) {
                // Fallback to MediaMetadataRetriever
            }
        }

        val retriever = MediaMetadataRetriever()
        return try {
            val pfd = try {
                contentResolver.openFileDescriptor(uri, "r")
            } catch (_: Exception) {
                null
            }
            if (pfd != null) {
                pfd.use {
                    retriever.setDataSource(it.fileDescriptor)
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                }
            } else if (uri.path != null && File(uri.path!!).exists()) {
                retriever.setDataSource(uri.path)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
            } else {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    /**
     * Resolves and decodes an orientation-corrected thumbnail preview for [item].
     *
     * @param item Database record containing asset metadata and cryptographic mapping.
     * @param masterKey Active Master Vault Key required to unwrap remote DEK envelopes.
     * @return Decoded in-memory [Bitmap], or null if download/decryption failed.
     */
    suspend fun loadThumbnail(
        item: MediaWithCryptoDetails,
        masterKey: MasterVaultKey?,
        targetPx: Int = 256
    ): Bitmap? = withContext(Dispatchers.IO) {
        val fileId = item.asset.fileId

        // Tier 1: Check L1 In-Memory Cache
        memoryCache.get(fileId)?.let { return@withContext it }

        // Tier 2: Check L2 Local Disk Cache
        val diskFile = File(diskCacheDir, "$fileId.webp")
        if (diskFile.exists() && diskFile.length() > 0) {
            val diskBitmap = decodeSemaphore.withPermit {
                memoryCache.get(fileId)?.let { return@withPermit it }
                withContext(Dispatchers.Default) {
                    try {
                        decodeHardwareBitmap(diskFile, targetPx)?.also {
                            memoryCache.put(fileId, it)
                        }
                    } catch (_: Exception) {
                        diskFile.delete()
                        null
                    }
                }
            }
            if (diskBitmap != null) {
                return@withContext diskBitmap
            }
        }

        // Tier 3: Remote Zero-Knowledge CAS Fetch & Decryption
        val thumbHash = item.cryptoMapping?.thumbnailCiphertextHash
            ?: item.asset.thumbnailHash
            ?: return@withContext null
        val wrappedDek = item.cryptoMapping?.wrappedThumbnailDek
            ?: item.cryptoMapping?.wrappedDek
            ?: return@withContext null

        if (masterKey == null) return@withContext null

        val remoteResult = downloadSemaphore.withPermit {
            // Re-check L1 and L2 within permit in case a concurrent fetch completed
            memoryCache.get(fileId)?.let { return@withPermit it }
            if (diskFile.exists() && diskFile.length() > 0) {
                try {
                    val bitmap = decodeSemaphore.withPermit {
                        memoryCache.get(fileId) ?: withContext(Dispatchers.Default) {
                            decodeHardwareBitmap(diskFile, targetPx)?.also {
                                memoryCache.put(fileId, it)
                            }
                        }
                    }
                    if (bitmap != null) return@withPermit bitmap
                } catch (_: Exception) {
                    diskFile.delete()
                }
            }

            var attempts = 0
            while (attempts < 3) {
                attempts++
                val tempFile = File.createTempFile("thumb_dl_", ".bin", context.cacheDir)
                try {
                    when (val result = assetClient.downloadAsset(thumbHash, tempFile)) {
                        is NetworkResult.Success -> {
                            try {
                                val encryptedBytes = result.data.readBytes()
                                val bitmap = withContext(Dispatchers.Default) {
                                    val rawDek = AesGcmEnvelope.unwrapKey(wrappedDek, masterKey.keyBytes)
                                    val plaintextWebp = AesGcmEnvelope.decrypt(encryptedBytes, rawDek)
                                    withContext(Dispatchers.IO) {
                                        diskFile.writeBytes(plaintextWebp)
                                    }
                                    decodeSemaphore.withPermit {
                                        decodeHardwareBitmap(plaintextWebp, targetPx)
                                            ?: decodeHardwareBitmap(diskFile, targetPx)
                                    }
                                }
                                if (bitmap != null) {
                                    memoryCache.put(fileId, bitmap)
                                    return@withPermit bitmap
                                }
                            } catch (_: Exception) {
                                return@withPermit null
                            }
                        }
                        is NetworkResult.Http.RateLimited -> {
                            val waitMs = (result.retryAfterSeconds?.coerceAtLeast(1)?.times(1000L)) ?: 70L
                            delay(waitMs)
                            continue
                        }
                        else -> return@withPermit null
                    }
                } finally {
                    tempFile.delete()
                }
            }
            null
        }

        remoteResult
    }

    /**
     * Clears all cached preview bitmaps from the in-memory cache.
     */
    fun clearMemoryCache() {
        memoryCache.evictAll()
    }

    /**
     * Purges all plaintext decrypted thumbnail previews from the local disk cache.
     */
    fun clearDiskCache() {
        diskCacheDir.listFiles()?.forEach { it.delete() }
    }
}
