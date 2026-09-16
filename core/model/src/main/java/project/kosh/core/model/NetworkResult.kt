package project.kosh.core.model

sealed interface NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>
    data class HttpError(val code: Int, val serverMessage: String): NetworkResult<Nothing>
    data class NetworkError(val exception: Throwable): NetworkResult<Nothing>
}