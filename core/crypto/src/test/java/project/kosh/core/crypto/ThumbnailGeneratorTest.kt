package project.kosh.core.crypto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
class ThumbnailGeneratorTest {

    @Test
    fun generateThumbnail_downscalesLargeImageToWithin512px() {
        // Create an uncompressed 1200x800 bitmap
        val origBitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        origBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        val imageBytes = stream.toByteArray()

        val thumbBytes = ThumbnailGenerator.generateThumbnail(imageBytes, maxDimension = 512)
        assertNotNull(thumbBytes)
        assertTrue(thumbBytes.isNotEmpty())

        val thumbBitmap = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
        assertNotNull(thumbBitmap)
        val maxDim = max(thumbBitmap.width, thumbBitmap.height)
        assertTrue("Max dimension should be <= 512, but was $maxDim", maxDim <= 512)
    }

    @Test
    fun generateThumbnail_preservesSmallImage() {
        val origBitmap = Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888)
        val stream = ByteArrayOutputStream()
        origBitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        val imageBytes = stream.toByteArray()

        val thumbBytes = ThumbnailGenerator.generateThumbnail(imageBytes, maxDimension = 512)
        val thumbBitmap = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
        assertNotNull(thumbBitmap)
        assertTrue(thumbBitmap.width <= 512 && thumbBitmap.height <= 512)
    }

    @Test
    fun compressBitmap_downscalesLargeBitmap() {
        val largeBitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val thumbBytes = ThumbnailGenerator.compressBitmap(largeBitmap, maxDimension = 512)
        assertNotNull(thumbBytes)
        assertTrue(thumbBytes.isNotEmpty())

        val decoded = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
        assertNotNull(decoded)
        val maxDim = max(decoded.width, decoded.height)
        assertTrue("Max dimension should be <= 512, but was $maxDim", maxDim <= 512)
    }

    @Test
    fun compressBitmap_preservesSmallBitmapDimensions() {
        val smallBitmap = Bitmap.createBitmap(120, 90, Bitmap.Config.ARGB_8888)
        val thumbBytes = ThumbnailGenerator.compressBitmap(smallBitmap, maxDimension = 512)
        assertNotNull(thumbBytes)
        assertTrue(thumbBytes.isNotEmpty())

        val decoded = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
        assertNotNull(decoded)
        assertEquals(120, decoded.width)
        assertEquals(90, decoded.height)
    }

    @Test
    fun compressBitmap_verticalAspect_downscalesProperly() {
        val tallBitmap = Bitmap.createBitmap(600, 1800, Bitmap.Config.ARGB_8888)
        val thumbBytes = ThumbnailGenerator.compressBitmap(tallBitmap, maxDimension = 512)
        assertNotNull(thumbBytes)
        val decoded = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
        assertNotNull(decoded)
        assertEquals(512, decoded.height)
        assertTrue(decoded.width <= 512)
    }

    @Test
    fun compressBitmap_squareAspect_downscalesToExactMaxDimension() {
        val squareBitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888)
        val thumbBytes = ThumbnailGenerator.compressBitmap(squareBitmap, maxDimension = 512)
        assertNotNull(thumbBytes)
        val decoded = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
        assertNotNull(decoded)
        assertEquals(512, decoded.width)
        assertEquals(512, decoded.height)
    }

    @Test
    fun compressBitmap_singlePixel_preservesDimensions() {
        val singlePixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val thumbBytes = ThumbnailGenerator.compressBitmap(singlePixel, maxDimension = 512)
        assertNotNull(thumbBytes)
        val decoded = BitmapFactory.decodeByteArray(thumbBytes, 0, thumbBytes.size)
        assertNotNull(decoded)
        assertEquals(1, decoded.width)
        assertEquals(1, decoded.height)
    }
}

