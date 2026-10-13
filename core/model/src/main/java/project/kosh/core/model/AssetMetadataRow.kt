package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AssetMetadataRow(
    val hash: String,

    @SerialName("size_bytes")
    val sizeBytes: Long,

    @SerialName("last_modified")
    val lastModified: Long,

    val tag: AssetTag
)


