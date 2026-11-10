package project.kosh.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import project.kosh.core.model.crdt.CrdtAction
import project.kosh.core.model.crdt.SyncDeltaBundle

@OptIn(ExperimentalSerializationApi::class)
class CrdtActionProtobufTest {

    private val protoBuf = ProtoBuf { }

    @Test
    fun testAssetCreateRoundTrip() {
        val action = CrdtAction.AssetCreate(
            actionId = "act-1",
            lamportClock = 42L,
            deviceId = "dev-android-1",
            timestampEpochMs = 1700000000000L,
            fileId = "file-uuid-1",
            mediaType = "image/jpeg",
            width = 1920,
            height = 1080,
            durationMs = null,
            fileSize = 204800L,
            capturedAt = 1699999990000L,
            plaintextSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ciphertextHash = "cas-blake3-hash-1",
            thumbnailCiphertextHash = "cas-blake3-thumb-1",
            wrappedDek = "0102030405060708090a0b0c0d0e0f10",
            deviceMake = "Google",
            deviceModel = "Pixel 8",
            lensModel = "Wide",
            focalLength = 6.8,
            aperture = 1.85,
            iso = 100,
            exposureTime = 0.005,
            latitude = 37.7749,
            longitude = -122.4194,
            altitude = 15.0
        )

        val bytes = protoBuf.encodeToByteArray(CrdtAction.serializer(), action)
        assertTrue(bytes.isNotEmpty())

        val decoded = protoBuf.decodeFromByteArray(CrdtAction.serializer(), bytes) as CrdtAction.AssetCreate
        assertEquals(action, decoded)
    }

    @Test
    fun testAssetDeleteRoundTrip() {
        val action = CrdtAction.AssetDelete(
            actionId = "act-del-1",
            lamportClock = 43L,
            deviceId = "dev-android-1",
            timestampEpochMs = 1700000010000L,
            fileId = "file-uuid-1"
        )

        val bytes = protoBuf.encodeToByteArray(CrdtAction.serializer(), action)
        val decoded = protoBuf.decodeFromByteArray(CrdtAction.serializer(), bytes) as CrdtAction.AssetDelete
        assertEquals(action, decoded)
    }

    @Test
    fun testAlbumActionsRoundTrip() {
        val albumCreate = CrdtAction.AlbumCreate(
            actionId = "act-alb-1",
            lamportClock = 44L,
            deviceId = "dev-android-1",
            timestampEpochMs = 1700000020000L,
            albumId = "album-1",
            title = "Vacation 2026",
            description = "Trip to Tokyo",
            coverFileId = "file-uuid-1"
        )
        val albumUpdate = CrdtAction.AlbumUpdateTitle(
            actionId = "act-alb-2",
            lamportClock = 45L,
            deviceId = "dev-android-1",
            timestampEpochMs = 1700000030000L,
            albumId = "album-1",
            newTitle = "Vacation 2026 Updated",
            newDescription = null
        )
        val albumAdd = CrdtAction.AlbumAddMedia(
            actionId = "act-alb-3",
            lamportClock = 46L,
            deviceId = "dev-android-1",
            timestampEpochMs = 1700000040000L,
            albumId = "album-1",
            fileId = "file-uuid-1"
        )
        val albumRemove = CrdtAction.AlbumRemoveMedia(
            actionId = "act-alb-4",
            lamportClock = 47L,
            deviceId = "dev-android-1",
            timestampEpochMs = 1700000050000L,
            albumId = "album-1",
            fileId = "file-uuid-1"
        )
        val albumDelete = CrdtAction.AlbumDelete(
            actionId = "act-alb-5",
            lamportClock = 48L,
            deviceId = "dev-android-1",
            timestampEpochMs = 1700000060000L,
            albumId = "album-1"
        )

        val actions = listOf(albumCreate, albumUpdate, albumAdd, albumRemove, albumDelete)
        for (act in actions) {
            val bytes = protoBuf.encodeToByteArray(CrdtAction.serializer(), act)
            val decoded = protoBuf.decodeFromByteArray(CrdtAction.serializer(), bytes)
            assertEquals(act, decoded)
        }
    }

    @Test
    fun testSyncDeltaBundleRoundTrip() {
        val action1 = CrdtAction.AssetCreate(
            actionId = "act-1",
            lamportClock = 10L,
            deviceId = "dev-1",
            timestampEpochMs = 1000L,
            fileId = "file-1",
            mediaType = "image/png",
            width = null,
            height = null,
            durationMs = null,
            fileSize = 1024L,
            capturedAt = 900L,
            plaintextSha256 = "hash1",
            ciphertextHash = "c-hash1",
            thumbnailCiphertextHash = null,
            wrappedDek = "dek1"
        )
        val action2 = CrdtAction.AssetDelete(
            actionId = "act-2",
            lamportClock = 15L,
            deviceId = "dev-1",
            timestampEpochMs = 2000L,
            fileId = "file-0"
        )

        val bundle = SyncDeltaBundle(
            bundleId = "bundle-uuid-123",
            deviceId = "dev-1",
            minClock = 10L,
            maxClock = 15L,
            actions = listOf(action1, action2)
        )

        val bytes = protoBuf.encodeToByteArray(SyncDeltaBundle.serializer(), bundle)
        val decoded = protoBuf.decodeFromByteArray(SyncDeltaBundle.serializer(), bytes)

        assertEquals(bundle.bundleId, decoded.bundleId)
        assertEquals(bundle.deviceId, decoded.deviceId)
        assertEquals(bundle.minClock, decoded.minClock)
        assertEquals(bundle.maxClock, decoded.maxClock)
        assertEquals(2, decoded.actions.size)
        assertEquals(action1, decoded.actions[0])
        assertEquals(action2, decoded.actions[1])
    }
}
