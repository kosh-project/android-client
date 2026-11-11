package project.kosh.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import project.kosh.core.model.snapshot.GallerySnapshotBundle
import project.kosh.core.model.snapshot.SnapshotAlbum
import project.kosh.core.model.snapshot.SnapshotAlbumCrossRef
import project.kosh.core.model.snapshot.SnapshotMediaAsset
import project.kosh.core.model.snapshot.SnapshotMediaCrypto

@OptIn(ExperimentalSerializationApi::class)
class GallerySnapshotBundleTest {

    private val protoBuf = ProtoBuf { }

    @Test
    fun testGallerySnapshotBundleRoundTrip() {
        val asset = SnapshotMediaAsset(
            fileId = "file-uuid-1",
            thumbnailHash = "thumb-hash-1",
            mediaType = "image/jpeg",
            width = 3840,
            height = 2160,
            durationMs = null,
            fileSize = 4096000L,
            deviceMake = "Google",
            deviceModel = "Pixel 8 Pro",
            lensModel = "Main",
            focalLength = 6.9,
            aperture = 1.68,
            iso = 125,
            exposureTime = 0.002,
            latitude = 37.7749,
            longitude = -122.4194,
            altitude = 12.5,
            capturedAt = 1700000000000L,
            createdAt = 1700000001000L,
            updatedAt = 1700000002000L,
            assetTag = AssetTag.GALLERY_ITEM,
            lamportClock = 100L,
            deviceId = "dev-1",
            isDeleted = false
        )

        val crypto = SnapshotMediaCrypto(
            fileId = "file-uuid-1",
            plaintextSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ciphertextHash = "cas-hash-asset-1",
            thumbnailCiphertextHash = "cas-hash-thumb-1",
            wrappedDek = "a1b2c3d4e5f60102030405060708090a0b0c0d0e0f1011121314151617181920",
            wrappedThumbnailDek = "b1b2c3d4e5f60102030405060708090a0b0c0d0e0f1011121314151617181921",
            uploadStatus = UploadStatus.SYNCED,
            lastSyncedAt = 1700000003000L,
            errorMessage = null,
            lamportClock = 101L,
            deviceId = "dev-1",
            isDeleted = false,
            thumbnailFileId = "thumb-uuid-1"
        )

        val album = SnapshotAlbum(
            albumId = "album-uuid-1",
            title = "Tokyo Vacation",
            description = "Trip memories",
            coverFileId = "file-uuid-1",
            createdAt = 1700000004000L,
            updatedAt = 1700000005000L,
            lamportClock = 102L,
            deviceId = "dev-1",
            isDeleted = false
        )

        val crossRef = SnapshotAlbumCrossRef(
            albumId = "album-uuid-1",
            fileId = "file-uuid-1",
            addedAt = 1700000006000L,
            positionIndex = 0,
            lamportClock = 103L,
            deviceId = "dev-1",
            isDeleted = false
        )

        val bundle = GallerySnapshotBundle(
            version = 1,
            generatedAtEpochMs = 1700000007000L,
            watermarkSeq = 500L,
            assets = listOf(asset),
            cryptoMappings = listOf(crypto),
            albums = listOf(album),
            albumCrossRefs = listOf(crossRef)
        )

        val bytes = protoBuf.encodeToByteArray(GallerySnapshotBundle.serializer(), bundle)
        assertTrue(bytes.isNotEmpty())

        val decoded = protoBuf.decodeFromByteArray(GallerySnapshotBundle.serializer(), bytes)
        assertEquals(bundle, decoded)
        assertEquals(1, decoded.version)
        assertEquals(500L, decoded.watermarkSeq)
        assertEquals(1, decoded.assets.size)
        assertEquals(1, decoded.cryptoMappings.size)
        assertEquals(1, decoded.albums.size)
        assertEquals(1, decoded.albumCrossRefs.size)
    }

    @Test
    fun testEmptySnapshotBundleRoundTrip() {
        val emptyBundle = GallerySnapshotBundle(
            version = 1,
            generatedAtEpochMs = 1700000000000L,
            watermarkSeq = 0L,
            assets = emptyList(),
            cryptoMappings = emptyList(),
            albums = emptyList(),
            albumCrossRefs = emptyList()
        )

        val bytes = protoBuf.encodeToByteArray(GallerySnapshotBundle.serializer(), emptyBundle)
        val decoded = protoBuf.decodeFromByteArray(GallerySnapshotBundle.serializer(), bytes)
        assertEquals(emptyBundle, decoded)
    }
}
