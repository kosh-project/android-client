package project.kosh.core.database.crdt

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
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
            if (mediaType.startsWith("image/", ignoreCase = true)) {
                ThumbnailGenerator.generateThumbnail(bytes)
            } else null
        } catch (_: Exception) {
            null
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

        // Step 8: Atomic Persistence in Room
        onProgress?.invoke(UploadStage.Finalizing)
        val localClock = lamportClockManager.tick()
        val deviceId = lamportClockManager.getDeviceId()

        val assetEntity = MediaAssetEntity(
            fileId = fileId,
            thumbnailHash = thumbCiphertextHash,
            mediaType = mediaType,
            width = width,
            height = height,
            durationMs = null,
            fileSize = bytes.size.toLong(),
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

        // Step 9: Enqueue Delta-CRDT Action for peer synchronization
        val crdtAction = CrdtAction.AssetCreate(
            actionId = UUID.randomUUID().toString(),
            lamportClock = localClock,
            deviceId = deviceId,
            timestampEpochMs = now,
            fileId = fileId,
            mediaType = mediaType,
            width = width,
            height = height,
            durationMs = null,
            fileSize = bytes.size.toLong(),
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

        onProgress?.invoke(UploadStage.Completed)
        return UploadResult.Success(assetEntity.toDomainModel(), isDeduplicated = false)
    }

    /**
     * Uploads media referenced by Android content [Uri].
     */
    suspend fun uploadFromUri(
        contentResolver: ContentResolver,
        uri: Uri,
        masterVaultKey: MasterVaultKey,
        mediaType: String? = null,
        originalFileName: String? = null,
        customFileId: String? = null,
        onProgress: ((UploadStage) -> Unit)? = null
    ): UploadResult {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: run {
                val err = "Could not open input stream from uri: $uri"
                onProgress?.invoke(UploadStage.Failed(err))
                return UploadResult.Error(err)
            }

        val resolvedMediaType = mediaType ?: contentResolver.getType(uri) ?: "image/jpeg"

        return uploadFromBytes(
            bytes = bytes,
            mediaType = resolvedMediaType,
            masterVaultKey = masterVaultKey,
            originalFileName = originalFileName,
            customFileId = customFileId,
            onProgress = onProgress
        )
    }

    private data class ParsedMetadata(
        val width: Int?,
        val height: Int?,
        val exif: ExifMetadata,
        val capturedAt: Long
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
}
