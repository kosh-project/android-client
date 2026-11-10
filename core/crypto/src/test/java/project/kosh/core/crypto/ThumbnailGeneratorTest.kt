package project.kosh.core.crypto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
}
