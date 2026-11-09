package project.kosh.core.database

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import project.kosh.core.model.AssetTag
import project.kosh.core.model.UploadStatus

class DatabaseConverterTest {

    private lateinit var converter: DatabaseConverter

    @Before
    fun setUp() {
        converter = DatabaseConverter()
    }

    @Test
    fun assetTag_conversion_preservesValues() {
        AssetTag.entries.forEach { tag ->
            val id = converter.fromAssetTag(tag)
            assertEquals(tag.id, id)
            val convertedTag = converter.toAssetTag(id)
            assertEquals(tag, convertedTag)
        }
    }

    @Test
    fun toAssetTag_withInvalidId_returnsGalleryItemFallback() {
        val converted = converter.toAssetTag(999)
        assertEquals(AssetTag.GALLERY_ITEM, converted)
    }

    @Test
    fun uploadStatus_conversion_preservesValues() {
        UploadStatus.entries.forEach { status ->
            val name = converter.fromUploadStatus(status)
            assertEquals(status.name, name)
            val convertedStatus = converter.toUploadStatus(name)
            assertEquals(status, convertedStatus)
        }
    }

    @Test
    fun toUploadStatus_withInvalidValue_returnsFailed() {
        val converted = converter.toUploadStatus("CORRUPTED_VALUE")
        assertEquals(UploadStatus.FAILED, converted)
    }
}
