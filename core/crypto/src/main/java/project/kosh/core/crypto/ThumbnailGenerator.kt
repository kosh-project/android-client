package project.kosh.core.crypto

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max

/**
 * Pre-upload image thumbnail generator downscaling images to <= 512px.
 */
object ThumbnailGenerator {
    const val MAX_THUMBNAIL_DIMENSION = 512
    const val COMPRESS_QUALITY = 80

    /**
     * Generates a downscaled JPEG thumbnail (max dimension <= [maxDimension]) from raw image bytes.
     */
    fun generateThumbnail(
        imageBytes: ByteArray,
        maxDimension: Int = MAX_THUMBNAIL_DIMENSION,
        quality: Int = COMPRESS_QUALITY
    ): ByteArray {
        require(imageBytes.isNotEmpty()) { "imageBytes cannot be empty" }

        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, boundsOptions)

        val origWidth = boundsOptions.outWidth
        val origHeight = boundsOptions.outHeight

        if (origWidth <= 0 || origHeight <= 0) {
            throw IllegalArgumentException("Could not decode image bounds (width=$origWidth, height=$origHeight)")
        }

        var inSampleSize = 1
        val maxOriginal = max(origWidth, origHeight)
        while (maxOriginal / (inSampleSize * 2) >= maxDimension) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
            ?: throw IllegalStateException("Failed to decode bitmap from bytes")

        // Correct for EXIF orientation if present
        val orientedBitmap = try {
            val exif = ExifInterface(ByteArrayInputStream(imageBytes))
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true)
                if (rotated != decodedBitmap) {
                    decodedBitmap.recycle()
                }
                rotated
            } else {
                decodedBitmap
            }
        } catch (_: Exception) {
            decodedBitmap
        }

        val currentMax = max(orientedBitmap.width, orientedBitmap.height)
        val finalBitmap = if (currentMax > maxDimension) {
            val scale = maxDimension.toFloat() / currentMax
            val targetWidth = (orientedBitmap.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (orientedBitmap.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(orientedBitmap, targetWidth, targetHeight, true)
            if (scaled != orientedBitmap) {
                orientedBitmap.recycle()
            }
            scaled
        } else {
            orientedBitmap
        }

        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        finalBitmap.recycle()
        return outputStream.toByteArray()
    }

    /**
     * Downscales and compresses a [Bitmap] into JPEG thumbnail bytes.
     * The resulting image will have max(width, height) <= [maxDimension].
     */
    fun compressBitmap(
        bitmap: Bitmap,
        maxDimension: Int = MAX_THUMBNAIL_DIMENSION,
        quality: Int = COMPRESS_QUALITY
    ): ByteArray {
        val currentMax = max(bitmap.width, bitmap.height)
        val finalBitmap = if (currentMax > maxDimension) {
            val scale = maxDimension.toFloat() / currentMax
            val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        if (finalBitmap != bitmap) {
            finalBitmap.recycle()
        }
        return outputStream.toByteArray()
    }

    /**
     * Generates a downscaled thumbnail from an [InputStream].
     */
    fun generateThumbnail(
        inputStream: InputStream,
        maxDimension: Int = MAX_THUMBNAIL_DIMENSION,
        quality: Int = COMPRESS_QUALITY
    ): ByteArray {
        val bytes = inputStream.readBytes()
        return generateThumbnail(bytes, maxDimension, quality)
    }
}
