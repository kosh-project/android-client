package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UploadResponse(
    @SerialName("file_name")
    val fileName: String,
    val hash: String? = null,
    val error: String? = null
) {
    val isSuccess: Boolean get() = !hash.isNullOrBlank() && error.isNullOrBlank()
}
