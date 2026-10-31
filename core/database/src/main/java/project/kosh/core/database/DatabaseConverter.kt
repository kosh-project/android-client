package project.kosh.core.database

import androidx.core.view.WindowInsetsCompat
import androidx.room.TypeConverter
import project.kosh.core.model.AssetTag
import project.kosh.core.model.UploadStatus

class DatabaseConverter {

    @TypeConverter
    fun fromAssetTag(tag: AssetTag): Int = tag.id

    @TypeConverter
    fun toAssetTag(id : Int): AssetTag = AssetTag.fromId(id)

    @TypeConverter
    fun fromUploadStatus(status: UploadStatus): String = status.name

    @TypeConverter
    fun toUploadStatus(value: String): UploadStatus = runCatching {
        UploadStatus.valueOf(value)
    }.getOrDefault(UploadStatus.FAILED)
}