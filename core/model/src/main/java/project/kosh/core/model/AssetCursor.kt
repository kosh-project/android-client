package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AssetCursor (
    @SerialName("before_time")
    val beforeTime: Long,

    @SerialName("before_id")
    val beforeId: String,
)
