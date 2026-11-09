package project.kosh.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MediaModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun exifMetadata_serializationRoundTrip() {
        val exif = ExifMetadata(
            deviceMake = "Apple",
            deviceModel = "iPhone 15 Pro",
            lensModel = "iPhone 15 Pro back camera 24mm f/1.78",
            focalLength = 6.76,
            aperture = 1.78,
            iso = 80,
            exposureTime = 0.002,
            latitude = 40.7128,
            longitude = -74.0060,
            altitude = 10.0
        )

        val encoded = json.encodeToString(ExifMetadata.serializer(), exif)
        val decoded = json.decodeFromString(ExifMetadata.serializer(), encoded)

        assertEquals(exif, decoded)
    }

    @Test
    fun mediaAsset_serializationRoundTrip() {
        val asset = MediaAsset(
            fileId = "test-uuid-99",
            thumbnailHash = "thumb-hash-blake3",
            mediaType = "image/png",
            width = 1080,
            height = 1920,
            fileSize = 102400L,
            exif = ExifMetadata(deviceMake = "Samsung", deviceModel = "Galaxy S24"),
            capturedAt = 1710000000000L,
            assetTag = AssetTag.GALLERY_ITEM
        )

        val encoded = json.encodeToString(MediaAsset.serializer(), asset)
        val decoded = json.decodeFromString(MediaAsset.serializer(), encoded)

        assertEquals("test-uuid-99", decoded.fileId)
        assertEquals("thumb-hash-blake3", decoded.thumbnailHash)
        assertEquals("image/png", decoded.mediaType)
        assertEquals(1080, decoded.width)
        assertEquals(1920, decoded.height)
        assertEquals(102400L, decoded.fileSize)
        assertEquals("Samsung", decoded.exif.deviceMake)
        assertEquals(AssetTag.GALLERY_ITEM, decoded.assetTag)
    }

    @Test
    fun mediaCryptoMapping_serializationRoundTrip() {
        val crypto = MediaCryptoMapping(
            fileId = "f-123",
            plaintextHash = "plain-blake3-hash",
            ciphertextHash = "cas-blake3-hash",
            thumbnailCiphertextHash = "thumb-cas-blake3-hash",
            wrappedDek = "0123456789abcdef".repeat(7) + "01234567",
            wrappedThumbnailDek = null,
            uploadStatus = UploadStatus.SYNCED,
            lastSyncedAt = 1710000500000L
        )

        val encoded = json.encodeToString(MediaCryptoMapping.serializer(), crypto)
        val decoded = json.decodeFromString(MediaCryptoMapping.serializer(), encoded)

        assertEquals(crypto, decoded)
    }

    @Test
    fun albumAndAlbumWithMediaData_serializationRoundTrip() {
        val album = Album(
            albumId = "alb-1",
            title = "Trip to Tokyo",
            description = "Shibuya and Shinjuku",
            coverFileId = "photo-1"
        )

        val asset = MediaAsset(
            fileId = "photo-1",
            mediaType = "image/jpeg",
            fileSize = 2048L,
            capturedAt = 1700000000000L
        )

        val albumData = AlbumWithMediaData(
            album = album,
            mediaAssets = listOf(asset),
            coverAsset = asset
        )

        val encoded = json.encodeToString(AlbumWithMediaData.serializer(), albumData)
        val decoded = json.decodeFromString(AlbumWithMediaData.serializer(), encoded)

        assertEquals("alb-1", decoded.album.albumId)
        assertEquals("Trip to Tokyo", decoded.album.title)
        assertEquals(1, decoded.mediaAssets.size)
        assertEquals("photo-1", decoded.coverAsset?.fileId)
    }
}
