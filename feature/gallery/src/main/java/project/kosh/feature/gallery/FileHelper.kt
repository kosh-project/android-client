package project.kosh.feature.gallery

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

object FileHelper {

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