package project.kosh.feature.gallery.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Utility for memory-safe subsampled local image decoding.
 *
 * Avoids loading full-resolution multi-megapixel camera bitmaps into the JVM heap
 * by first querying dimensions with [BitmapFactory.Options.inJustDecodeBounds] and then
 * decoding with a calculated power-of-two [BitmapFactory.Options.inSampleSize].
 */
internal object SubsampledImageDecoder {

    /**
     * Decodes a downsampled preview [Bitmap] from a content [Uri].
     *
     * @param contentResolver Resolver used to open the image input stream.
     * @param uri MediaStore or file URI pointing to the target image.
     * @param targetSize Maximum width or height constraint in pixels.
     * @return Downsampled [Bitmap], or null if decoding failed or the stream was unreadable.
     */
    fun decode(
        contentResolver: ContentResolver,
        uri: Uri,
        targetSize: Int
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
            val maxDim = maxOf(origWidth, origHeight)
            while (maxDim / (inSampleSize * 2) >= targetSize) {
                inSampleSize *= 2
            }
            val options = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
            }
            contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (_: Exception) {
            null
        }
    }
}
