package project.kosh.core.database.crdt

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import project.kosh.core.crypto.AesGcmEnvelope
import project.kosh.core.crypto.LamportClockManager
import project.kosh.core.crypto.MasterVaultKey
import project.kosh.core.crypto.MediaHasher
import project.kosh.core.crypto.ThumbnailGenerator
import project.kosh.core.database.KoshDatabase
import project.kosh.core.database.dao.MediaAssetDao
import project.kosh.core.database.dao.MediaCryptoDao
import project.kosh.core.database.dao.SyncQueueDao
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.database.entity.SyncQueueEntity
import project.kosh.core.model.AssetTag
import project.kosh.core.model.ExifMetadata
import project.kosh.core.model.MediaAsset
import project.kosh.core.model.NetworkResult
import project.kosh.core.model.UploadStage
import project.kosh.core.model.UploadStatus
import project.kosh.core.model.crdt.CrdtAction
import project.kosh.core.network.AssetClient
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a media upload attempt through [MediaUploadPipeline].
 */
sealed interface UploadResult {
    data class Success(val asset: MediaAsset, val isDeduplicated: Boolean) : UploadResult
    data class Error(val message: String, val throwable: Throwable? = null) : UploadResult
}

/**
 * Zero-Knowledge pre-upload and deduplication pipeline.
 *
 * Implements:
 * 1. Hardware-accelerated client-side SHA-256 deduplication.
 * 2. EXIF metadata extraction and downscaled preview thumbnail generation.
 * 3. Random 32-byte Data Encryption Key (DEK) generation and 60-byte envelope wrapping under $K_{vault}$.
 * 4. AES-256-GCM authenticated payload encryption.
 * 5. Streaming Content-Addressable Storage (CAS) upload to server `/api/v1/assets`.
 * 6. Atomic SQLite Room persistence and offline Delta-CRDT queue staging.
 */
@OptIn(ExperimentalSerializationApi::class)
@Singleton
class MediaUploadPipeline @Inject constructor(
    private val assetClient: AssetClient,
    private val mediaAssetDao: MediaAssetDao,
    private val mediaCryptoDao: MediaCryptoDao,
    private val syncQueueDao: SyncQueueDao,
    private val lamportClockManager: LamportClockManager,
    private val database: KoshDatabase,
    @ApplicationContext private val context: Context? = null
) {
    private val protoBuf = ProtoBuf { }

    /**
     * Uploads media bytes into Kosh's Zero-Knowledge vault.
     *
     * @param bytes Raw binary bytes of the media item.
     * @param mediaType MIME type (e.g. "image/jpeg", "image/png", "video/mp4").
     * @param masterVaultKey Active Master Vault Key used to wrap the per-file DEK.
     * @param originalFileName Optional user-facing file name.
     * @param customCapturedAt Optional timestamp override.
     * @param customExif Optional pre-extracted EXIF metadata.
     * @param onProgress Optional callback reporting granular upload lifecycle stages.
     * @return [UploadResult.Success] on success (with deduplication status), or [UploadResult.Error].
     */
    suspend fun uploadFromBytes(
        bytes: ByteArray,
        mediaType: String,
        masterVaultKey: MasterVaultKey,
        originalFileName: String? = null,
        customCapturedAt: Long? = null,
        customExif: ExifMetadata? = null,
        customFileId: String? = null,
        customThumbnailBytes: ByteArray? = null,
        onProgress: ((UploadStage) -> Unit)? = null
    ): UploadResult {
        if (bytes.isEmpty()) {
            val err = "Cannot upload empty media bytes"
            onProgress?.invoke(UploadStage.Failed(err))
            return UploadResult.Error(err)
        }

        // Step 1: Compute plaintext SHA-256 (hardware-accelerated)
        onProgress?.invoke(UploadStage.Hashing(0.5f))
        val plaintextSha256 = MediaHasher.computeSha256(bytes)
        onProgress?.invoke(UploadStage.Hashing(1.0f))

        // Step 2: Local Pre-Upload Deduplication Check
        val existingCrypto = mediaCryptoDao.getByPlaintextSha256(plaintextSha256)
        if (existingCrypto != null && existingCrypto.uploadStatus == UploadStatus.SYNCED) {
            val existingAsset = mediaAssetDao.getAssetById(existingCrypto.fileId)
            if (existingAsset != null) {
                onProgress?.invoke(UploadStage.Completed)
                return UploadResult.Success(existingAsset.toDomainModel(), isDeduplicated = true)
            }
        }

        val fileId = customFileId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Step 3: Geometry & EXIF extraction
        val (width, height, parsedExif, capturedAt) = extractExifAndDimensions(bytes, customCapturedAt, customExif)

        // Step 4: Thumbnail Generation
        onProgress?.invoke(UploadStage.GeneratingThumbnail)
        val thumbnailBytes = try {
            val extracted = if (customThumbnailBytes != null) {
                customThumbnailBytes
            } else if (mediaType.startsWith("image/", ignoreCase = true)) {
                ThumbnailGenerator.generateThumbnail(bytes)
            } else if (mediaType.startsWith("video/", ignoreCase = true)) {
                extractVideoThumbnailFromBytes(bytes)
            } else {
                null
            }
            if (extracted == null && mediaType.startsWith("video/", ignoreCase = true)) {
                // Guaranteed fallback thumbnail for video items so remote devices never hang
                val fallbackBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.DKGRAY)
                }
                ThumbnailGenerator.compressBitmap(fallbackBitmap)
            } else {
                extracted
            }
        } catch (_: Exception) {
            if (mediaType.startsWith("video/", ignoreCase = true)) {
                try {
                    val fallbackBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.DKGRAY)
                    }
                    ThumbnailGenerator.compressBitmap(fallbackBitmap)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
        }

        // Cache thumbnail locally if context is available
        if (thumbnailBytes != null && context != null) {
            try {
                val thumbDir = File(context.cacheDir, "kosh_thumbnails").apply { mkdirs() }
                File(thumbDir, "$fileId.webp").writeBytes(thumbnailBytes)
            } catch (_: Exception) {
                // Ignore disk cache write failure
            }
        }

        // Step 5: Cryptographic DEK Envelope Wrapping
        onProgress?.invoke(UploadStage.Encrypting)
        val dek = ByteArray(32)
        SecureRandom().nextBytes(dek)
        val wrappedDekHex = AesGcmEnvelope.wrapKey(dek, masterVaultKey.keyBytes)

        // Step 6: Encryption
        val encryptedAsset = AesGcmEnvelope.encrypt(bytes, dek)
        val encryptedThumbnail = thumbnailBytes?.let { AesGcmEnvelope.encrypt(it, dek) }

        // Step 7: Server CAS Upload (Thumbnails categorized as GALLERY_META)
        val thumbCiphertextHash = if (encryptedThumbnail != null) {
            when (val thumbRes = assetClient.uploadBytes(encryptedThumbnail, AssetTag.GALLERY_META, "${fileId}_thumb.bin")) {
                is NetworkResult.Success -> thumbRes.data
                else -> null
            }
        } else null

        val assetFileName = originalFileName ?: "$fileId.bin"
        onProgress?.invoke(UploadStage.Uploading(0f, 0L, encryptedAsset.size.toLong()))
        val startTime = System.currentTimeMillis()
        var lastTime = startTime
        var lastBytes = 0L
        var currentSpeed = 0L

        val assetUploadRes = assetClient.uploadBytes(
            bytes = encryptedAsset,
            tag = AssetTag.GALLERY_ITEM,
            originalFileName = assetFileName,
            onProgress = { sent, total ->
                val nowTime = System.currentTimeMillis()
                val timeDelta = nowTime - lastTime
                if (timeDelta >= 400) {
                    val bytesDelta = sent - lastBytes
                    currentSpeed = (bytesDelta * 1000L) / timeDelta
                    lastTime = nowTime
                    lastBytes = sent
                }
                val progress = if (total > 0) sent.toFloat() / total else 0f
                onProgress?.invoke(UploadStage.Uploading(progress, sent, total, currentSpeed))
            }
        )
        val assetCiphertextHash = when (assetUploadRes) {
            is NetworkResult.Success -> assetUploadRes.data
            is NetworkResult.Failure -> {
                val err = "Failed to upload encrypted asset to CAS: $assetUploadRes"
                onProgress?.invoke(UploadStage.Failed(err))
                return UploadResult.Error(err)
            }
        }

        // Step 8: Atomic Persistence in Room & Offline Sync Queue
        onProgress?.invoke(UploadStage.Finalizing)
        val assetEntity = persistAndEnqueue(
            fileId = fileId,
            mediaType = mediaType,
            width = width,
            height = height,
            durationMs = null,
            fileSize = bytes.size.toLong(),
            parsedExif = parsedExif,
            capturedAt = capturedAt,
            plaintextSha256 = plaintextSha256,
            assetCiphertextHash = assetCiphertextHash,
            thumbCiphertextHash = thumbCiphertextHash,
            wrappedDekHex = wrappedDekHex,
            now = now
        )

        onProgress?.invoke(UploadStage.Completed)
        return UploadResult.Success(assetEntity.toDomainModel(), isDeduplicated = false)
    }

    /**
     * Uploads media referenced by Android content [Uri] using streaming chunked I/O.
     *
     * Invariants:
     * 1. Eliminates monolithic in-memory byte arrays: uses 64 KiB buffers across SHA-256 hashing,
     *    AES-256-GCM encryption, and OkHttp network socket streaming.
     * 2. Video thumbnail generation queries [MediaMetadataRetriever] on native file descriptor
     *    without loading the video stream into heap memory.
     * 3. Emits granular byte-level upload progress (`sent / total`) across the upload lifecycle.
     */
    suspend fun uploadFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        masterVaultKey: MasterVaultKey,
        mediaType: String? = null,
        originalFileName: String? = null,
        customFileId: String? = null,
        customCapturedAt: Long? = null,
        onProgress: ((UploadStage) -> Unit)? = null
    ): UploadResult {
        val plaintextSize = resolveFileSize(contentResolver, uri)

        // Step 1: Compute plaintext SHA-256 in 64 KiB chunks and determine exact length
        onProgress?.invoke(UploadStage.Hashing(0f))
        val hashResult = try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                MediaHasher.computeSha256WithLength(inputStream, plaintextSize) { bytesHashed, total ->
                    if (total > 0L) {
                        onProgress?.invoke(UploadStage.Hashing(bytesHashed.toFloat() / total.toFloat()))
                    }
                }
            }
        } catch (_: Exception) {
            null
        } ?: run {
            val err = "Could not open input stream from uri: $uri"
            onProgress?.invoke(UploadStage.Failed(err))
            return UploadResult.Error(err)
        }
        val (plaintextSha256, actualBytesRead) = hashResult

        if (actualBytesRead == 0L || (plaintextSize == 0L && actualBytesRead == 0L)) {
            val err = "Cannot upload empty media bytes"
            onProgress?.invoke(UploadStage.Failed(err))
            return UploadResult.Error(err)
        }
        val effectivePlaintextSize = if (plaintextSize > 0L) plaintextSize else actualBytesRead
        onProgress?.invoke(UploadStage.Hashing(1.0f))

        // Step 2: Local Pre-Upload Deduplication Check
        val existingCrypto = mediaCryptoDao.getByPlaintextSha256(plaintextSha256)
        if (existingCrypto != null && existingCrypto.uploadStatus == UploadStatus.SYNCED) {
            val existingAsset = mediaAssetDao.getAssetById(existingCrypto.fileId)
            if (existingAsset != null) {
                onProgress?.invoke(UploadStage.Completed)
                return UploadResult.Success(existingAsset.toDomainModel(), isDeduplicated = true)
            }
        }

        val resolvedMediaType = mediaType ?: contentResolver.getType(uri) ?: "image/jpeg"
        val fileId = customFileId ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Step 3: Geometry & EXIF extraction without full-file heap buffers
        val (width, height, parsedExif, capturedAt, durationMs) = extractExifAndDimensionsFromUri(
            contentResolver = contentResolver,
            uri = uri,
            mediaType = resolvedMediaType,
            customCapturedAt = customCapturedAt
        )

        // Step 4: Thumbnail Generation using native fileDescriptor or downscaled stream
        onProgress?.invoke(UploadStage.GeneratingThumbnail)
        val thumbnailBytes = try {
            if (resolvedMediaType.startsWith("video/", ignoreCase = true)) {
                val videoBitmap: Bitmap? = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            contentResolver.loadThumbnail(uri, Size(512, 512), null)
                        } catch (_: Exception) {
                            extractVideoFrameFromUri(contentResolver, uri)
                        }
                    } else {
                        extractVideoFrameFromUri(contentResolver, uri)
                    }
                } catch (_: Exception) {
                    null
                }
                val compressed = videoBitmap?.let { bitmap ->
                    try {
                        ThumbnailGenerator.compressBitmap(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (compressed == null) {
                    val fallbackBitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.DKGRAY)
                    }
                    try {
                        ThumbnailGenerator.compressBitmap(fallbackBitmap)
                    } finally {
                        fallbackBitmap.recycle()
                    }
                } else {
                    compressed
                }
            } else if (resolvedMediaType.startsWith("image/", ignoreCase = true)) {
                val imageBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        contentResolver.loadThumbnail(uri, Size(512, 512), null)
                    } catch (_: Exception) {
                        decodeSampledBitmapFromUri(contentResolver, uri, 512)
                    }
                } else {
                    decodeSampledBitmapFromUri(contentResolver, uri, 512)
                }
                imageBitmap?.let { bitmap ->
                    try {
                        ThumbnailGenerator.compressBitmap(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        // Cache thumbnail locally if context is available
        if (thumbnailBytes != null && context != null) {
            try {
                val thumbDir = File(context.cacheDir, "kosh_thumbnails").apply { mkdirs() }
                File(thumbDir, "$fileId.webp").writeBytes(thumbnailBytes)
            } catch (_: Exception) {}
        }

        // Step 5: Cryptographic DEK Envelope Wrapping
        onProgress?.invoke(UploadStage.Encrypting)
        val dek = ByteArray(32)
        SecureRandom().nextBytes(dek)
        val wrappedDekHex = AesGcmEnvelope.wrapKey(dek, masterVaultKey.keyBytes)

        // Step 6: Server CAS Upload for Thumbnail
        val encryptedThumbnail = thumbnailBytes?.let { AesGcmEnvelope.encrypt(it, dek) }
        val thumbCiphertextHash = if (encryptedThumbnail != null) {
            when (val thumbRes = assetClient.uploadBytes(encryptedThumbnail, AssetTag.GALLERY_META, "${fileId}_thumb.bin")) {
                is NetworkResult.Success -> thumbRes.data
                else -> null
            }
        } else null

        // Step 7: Streaming CAS Upload for Main Asset
        val assetFileName = originalFileName ?: "$fileId.bin"
        val totalExpectedCiphertextBytes = effectivePlaintextSize + AesGcmEnvelope.IV_SIZE_BYTES + AesGcmEnvelope.TAG_SIZE_BYTES

        onProgress?.invoke(UploadStage.Uploading(0f, 0L, totalExpectedCiphertextBytes))
        var lastTime = System.currentTimeMillis()
        var lastBytes = 0L
        var currentSpeed = 0L

        val assetUploadRes = assetClient.uploadEncryptedUri(
            contentResolver = contentResolver,
            uri = uri,
            tag = AssetTag.GALLERY_ITEM,
            originalFileName = assetFileName,
            dek = dek,
            plaintextSize = effectivePlaintextSize,
            onProgress = { sent, total ->
                val nowTime = System.currentTimeMillis()
                val timeDelta = nowTime - lastTime
                if (timeDelta >= 400) {
                    val bytesDelta = sent - lastBytes
                    currentSpeed = (bytesDelta * 1000L) / timeDelta
                    lastTime = nowTime
                    lastBytes = sent
                }
                val progress = if (total > 0) sent.toFloat() / total else 0f
                onProgress?.invoke(UploadStage.Uploading(progress, sent, total, currentSpeed))
            }
        )
        val assetCiphertextHash = when (assetUploadRes) {
            is NetworkResult.Success -> assetUploadRes.data
            is NetworkResult.Failure -> {
                val err = "Failed to upload encrypted asset to CAS: $assetUploadRes"
                onProgress?.invoke(UploadStage.Failed(err))
                return UploadResult.Error(err)
            }
        }

        // Step 8: Atomic Persistence in Room & Delta-CRDT Queue
        onProgress?.invoke(UploadStage.Finalizing)
        val assetEntity = persistAndEnqueue(
            fileId = fileId,
            mediaType = resolvedMediaType,
            width = width,
            height = height,
            durationMs = durationMs,
            fileSize = effectivePlaintextSize,
            parsedExif = parsedExif,
            capturedAt = capturedAt,
            plaintextSha256 = plaintextSha256,
            assetCiphertextHash = assetCiphertextHash,
            thumbCiphertextHash = thumbCiphertextHash,
            wrappedDekHex = wrappedDekHex,
            now = now
        )

        onProgress?.invoke(UploadStage.Completed)
        return UploadResult.Success(assetEntity.toDomainModel(), isDeduplicated = false)
    }

    private suspend fun persistAndEnqueue(
        fileId: String,
        mediaType: String,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        fileSize: Long,
        parsedExif: ExifMetadata,
        capturedAt: Long,
        plaintextSha256: String,
        assetCiphertextHash: String,
        thumbCiphertextHash: String?,
        wrappedDekHex: String,
        now: Long
    ): MediaAssetEntity {
        val localClock = lamportClockManager.tick()
        val deviceId = lamportClockManager.getDeviceId()

        val assetEntity = MediaAssetEntity(
            fileId = fileId,
            thumbnailHash = thumbCiphertextHash,
            mediaType = mediaType,
            width = width,
            height = height,
            durationMs = durationMs,
            fileSize = fileSize,
            deviceMake = parsedExif.deviceMake,
            deviceModel = parsedExif.deviceModel,
            lensModel = parsedExif.lensModel,
            focalLength = parsedExif.focalLength,
            aperture = parsedExif.aperture,
            iso = parsedExif.iso,
            exposureTime = parsedExif.exposureTime,
            latitude = parsedExif.latitude,
            longitude = parsedExif.longitude,
            altitude = parsedExif.altitude,
            capturedAt = capturedAt,
            createdAt = now,
            updatedAt = now,
            assetTag = AssetTag.GALLERY_ITEM,
            lamportClock = localClock,
            deviceId = deviceId,
            isDeleted = false
        )

        val cryptoEntity = MediaCryptoMappingEntity(
            fileId = fileId,
            plaintextSha256 = plaintextSha256,
            ciphertextHash = assetCiphertextHash,
            thumbnailCiphertextHash = thumbCiphertextHash,
            wrappedDek = wrappedDekHex,
            uploadStatus = UploadStatus.SYNCED,
            lastSyncedAt = now,
            lamportClock = localClock,
            deviceId = deviceId,
            isDeleted = false,
            thumbnailFileId = if (thumbCiphertextHash != null) "${fileId}_thumb" else null
        )

        val crdtAction = CrdtAction.AssetCreate(
            actionId = UUID.randomUUID().toString(),
            lamportClock = localClock,
            deviceId = deviceId,
            timestampEpochMs = now,
            fileId = fileId,
            mediaType = mediaType,
            width = width,
            height = height,
            durationMs = durationMs,
            fileSize = fileSize,
            capturedAt = capturedAt,
            plaintextSha256 = plaintextSha256,
            ciphertextHash = assetCiphertextHash,
            thumbnailCiphertextHash = thumbCiphertextHash,
            wrappedDek = wrappedDekHex,
            deviceMake = parsedExif.deviceMake,
            deviceModel = parsedExif.deviceModel,
            lensModel = parsedExif.lensModel,
            focalLength = parsedExif.focalLength,
            aperture = parsedExif.aperture,
            iso = parsedExif.iso,
            exposureTime = parsedExif.exposureTime,
            latitude = parsedExif.latitude,
            longitude = parsedExif.longitude,
            altitude = parsedExif.altitude
        )
        val actionBytes = protoBuf.encodeToByteArray(CrdtAction.serializer(), crdtAction)
        val queueEntity = SyncQueueEntity(
            actionId = crdtAction.actionId,
            namespace = "gallery",
            actionType = "AssetCreate",
            entityId = fileId,
            payloadProtobuf = actionBytes,
            byteSize = actionBytes.size,
            lamportClock = localClock,
            status = "PENDING",
            createdAt = now
        )

        database.withTransaction {
            mediaAssetDao.insert(assetEntity)
            mediaCryptoDao.insert(cryptoEntity)
            syncQueueDao.enqueue(queueEntity)
        }

        return assetEntity
    }

    private fun extractVideoFrameFromUri(contentResolver: ContentResolver, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val pfd = try {
                contentResolver.openFileDescriptor(uri, "r")
            } catch (_: Exception) {
                null
            }
            if (pfd != null) {
                pfd.use {
                    try {
                        if (it.statSize > 0) {
                            retriever.setDataSource(it.fileDescriptor, 0, it.statSize)
                        } else {
                            retriever.setDataSource(it.fileDescriptor)
                        }
                    } catch (_: Exception) {
                        retriever.setDataSource(it.fileDescriptor)
                    }
                    retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.frameAtTime
                }
            } else if (uri.path != null && File(uri.path!!).exists()) {
                retriever.setDataSource(uri.path)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.frameAtTime
            } else if (context != null) {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.frameAtTime
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun extractVideoThumbnailFromBytes(bytes: ByteArray): ByteArray? {
        var tempFile: File? = null
        val retriever = MediaMetadataRetriever()
        return try {
            tempFile = File.createTempFile("kosh_vid_thumb_", ".mp4", context?.cacheDir)
            tempFile.writeBytes(bytes)
            retriever.setDataSource(tempFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(500_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.frameAtTime
            bitmap?.let { b ->
                try {
                    ThumbnailGenerator.compressBitmap(b)
                } finally {
                    b.recycle()
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
            tempFile?.delete()
        }
    }

    private data class ParsedMetadata(
        val width: Int?,
        val height: Int?,
        val exif: ExifMetadata,
        val capturedAt: Long,
        val durationMs: Long? = null
    )

    private fun extractExifAndDimensions(
        bytes: ByteArray,
        customCapturedAt: Long?,
        customExif: ExifMetadata?
    ): ParsedMetadata {
        var width: Int? = null
        var height: Int? = null
        var capturedAt = customCapturedAt ?: System.currentTimeMillis()
        var exif = customExif ?: ExifMetadata()

        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                width = bounds.outWidth
                height = bounds.outHeight
            }
        } catch (_: Exception) {}

        try {
            val exifInterface = ExifInterface(ByteArrayInputStream(bytes))
            val dateStr = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exifInterface.getAttribute(ExifInterface.TAG_DATETIME)

            if (dateStr != null && customCapturedAt == null) {
                val parsedDate = try {
                    SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dateStr)?.time
                } catch (_: Exception) { null }
                if (parsedDate != null) capturedAt = parsedDate
            }

            if (customExif == null) {
                val make = exifInterface.getAttribute(ExifInterface.TAG_MAKE)
                val model = exifInterface.getAttribute(ExifInterface.TAG_MODEL)
                val focalLength = exifInterface.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }
                val aperture = exifInterface.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }
                val iso = exifInterface.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 }
                val exposureTime = exifInterface.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }

                val latLong = FloatArray(2)
                val hasLatLong = exifInterface.getLatLong(latLong)
                val lat = if (hasLatLong) latLong[0].toDouble() else null
                val lon = if (hasLatLong) latLong[1].toDouble() else null
                val alt = exifInterface.getAltitude(0.0).takeIf { it != 0.0 }

                exif = ExifMetadata(
                    deviceMake = make,
                    deviceModel = model,
                    lensModel = null,
                    focalLength = focalLength,
                    aperture = aperture,
                    iso = iso,
                    exposureTime = exposureTime,
                    latitude = lat,
                    longitude = lon,
                    altitude = alt
                )
            }
        } catch (_: Exception) {}

        return ParsedMetadata(width, height, exif, capturedAt)
    }

    private fun resolveFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null)?.use { cursor ->
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx != -1 && cursor.moveToFirst()) {
                    val s = cursor.getLong(sizeIdx)
                    if (s >= 0L) size = s
                }
            }
        } catch (_: Exception) {}
        if (size <= 0L) {
            try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    if (pfd.statSize >= 0L) size = pfd.statSize
                }
            } catch (_: Exception) {}
        }
        if (size <= 0L && uri.path != null) {
            try {
                val f = File(uri.path!!)
                if (f.exists() && f.length() >= 0L) size = f.length()
            } catch (_: Exception) {}
        }
        return size
    }

    private fun decodeSampledBitmapFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        maxDimension: Int
    ): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            var inSampleSize = 1
            val maxOriginal = maxOf(origWidth, origHeight)
            while (maxOriginal / (inSampleSize * 2) >= maxDimension) {
                inSampleSize *= 2
            }
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractExifAndDimensionsFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        mediaType: String,
        customCapturedAt: Long?,
        customExif: ExifMetadata? = null
    ): ParsedMetadata {
        var width: Int? = null
        var height: Int? = null
        var capturedAt = customCapturedAt ?: System.currentTimeMillis()
        var exif = customExif ?: ExifMetadata()
        var durationMs: Long? = null

        if (mediaType.startsWith("image/", ignoreCase = true)) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    width = bounds.outWidth
                    height = bounds.outHeight
                }
            } catch (_: Exception) {}
        } else if (mediaType.startsWith("video/", ignoreCase = true)) {
            val retriever = MediaMetadataRetriever()
            try {
                val pfd = try {
                    contentResolver.openFileDescriptor(uri, "r")
                } catch (_: Exception) {
                    null
                }
                if (pfd != null) {
                    pfd.use {
                        try {
                            if (it.statSize > 0) {
                                retriever.setDataSource(it.fileDescriptor, 0, it.statSize)
                            } else {
                                retriever.setDataSource(it.fileDescriptor)
                            }
                        } catch (_: Exception) {
                            retriever.setDataSource(it.fileDescriptor)
                        }
                        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                        val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        if (w != null && w > 0) width = w
                        if (h != null && h > 0) height = h
                        if (dur != null && dur > 0) durationMs = dur
                    }
                } else if (uri.path != null && File(uri.path!!).exists()) {
                    retriever.setDataSource(uri.path)
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    if (w != null && w > 0) width = w
                    if (h != null && h > 0) height = h
                    if (dur != null && dur > 0) durationMs = dur
                }
            } catch (_: Exception) {
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        try {
            val exifInterface = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    ExifInterface(pfd.fileDescriptor)
                }
            } catch (_: Exception) { null } ?: contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream)
            }

            if (exifInterface != null) {
                val dateStr = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exifInterface.getAttribute(ExifInterface.TAG_DATETIME)

                if (dateStr != null && customCapturedAt == null) {
                    val parsedDate = try {
                        SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dateStr)?.time
                    } catch (_: Exception) { null }
                    if (parsedDate != null) capturedAt = parsedDate
                }

                if (customExif == null) {
                    val make = exifInterface.getAttribute(ExifInterface.TAG_MAKE)
                    val model = exifInterface.getAttribute(ExifInterface.TAG_MODEL)
                    val focalLength = exifInterface.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }
                    val aperture = exifInterface.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }
                    val iso = exifInterface.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 }
                    val exposureTime = exifInterface.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }

                    val latLong = FloatArray(2)
                    val hasLatLong = exifInterface.getLatLong(latLong)
                    val lat = if (hasLatLong) latLong[0].toDouble() else null
                    val lon = if (hasLatLong) latLong[1].toDouble() else null
                    val alt = exifInterface.getAltitude(0.0).takeIf { it != 0.0 }

                    exif = ExifMetadata(
                        deviceMake = make,
                        deviceModel = model,
                        lensModel = null,
                        focalLength = focalLength,
                        aperture = aperture,
                        iso = iso,
                        exposureTime = exposureTime,
                        latitude = lat,
                        longitude = lon,
                        altitude = alt
                    )
                }
            }
        } catch (_: Exception) {}

        return ParsedMetadata(width, height, exif, capturedAt, durationMs)
    }
}
