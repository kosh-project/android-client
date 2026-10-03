package project.kosh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-level JSON response envelope returned by the Content-Addressable Storage (CAS)
 * binary upload endpoint (`POST /api/v1/upload/{tag}`).
 *
 * **Backend Envelope Semantics:**
 * The Kosh Rust/Axum backend uses an untagged envelope pattern for upload results. Under certain
 * failure conditions (such as SQLite row insertion errors or disk persistence commit issues),
 * the server may still respond with HTTP 200 OK while returning an error message in the payload:
 * - Success envelope: `{"file_name": "...", "hash": "<blake3_hex_64_chars>"}`
 * - Failure envelope: `{"file_name": "...", "error": "Description of storage error"}`
 *
 * Consequently, callers must not rely solely on HTTP 200 status codes. Instead, inspect the
 * computed [isSuccess] property to confirm that the asset was committed and indexed.
 *
 * @property fileName The synthetic or original file identifier echoed by the server (`X-File-Name`).
 * @property hash The 64-character hex BLAKE3 content hash of the stored asset, present on successful commit.
 * @property error An error description string populated if the server encountered an error during ingestion.
 */
@Serializable
data class UploadResponse(
    @SerialName("file_name")
    val fileName: String,
    val hash: String? = null,
    val error: String? = null
) {
    /**
     * Determines whether the upload transaction succeeded.
     *
     * Returns `true` only if a non-blank BLAKE3 content [hash] was assigned and no [error] was reported.
     */
    val isSuccess: Boolean get() = !hash.isNullOrBlank() && error.isNullOrBlank()
}
