package project.kosh.feature.gallery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Utility helper for Android Storage Access Framework (SAF) and [android.content.ContentResolver] operations.
 *
 * Provides metadata resolution and file materialization helpers for media selection workflows.
 */
object FileHelper {

    /**
     * Resolves the display filename associated with a given [Uri].
     *
     * Queries the system [android.content.ContentResolver] for [OpenableColumns.DISPLAY_NAME] if
     * the URI scheme is `"content"`. Falls back to extracting the trailing path segment or
     * returning `"unknown_file"` if metadata is unavailable.
     *
     * @param context Android context used to access the content resolver.
     * @param uri The resource URI to inspect.
     * @return The resolved display name string.
     */
    fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null

        if (uri.scheme == "content") {
            context.contentResolver
                .query(uri, null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) result = cursor.getString(index)
                    }
            }
        }
        return result ?: uri.path?.substringAfterLast('/') ?: "unknown_file"
    }

    /**
     * Copies the content stream referenced by [uri] into a temporary file in the application cache directory.
     *
     * @param context Android context used to access the cache directory and content resolver.
     * @param uri The content URI to materialize.
     * @return The populated temporary [File] located in [Context.getCacheDir].
     */
    fun getFileFromUri(context: Context, uri: Uri): File {
        val fileName = getFileName(context, uri)

        val tempFile = File(context.cacheDir, fileName)

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        return tempFile
    }
}