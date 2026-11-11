package project.kosh.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import project.kosh.core.database.entity.MediaCryptoMappingEntity
import project.kosh.core.model.UploadStatus

/**
 * Data Access Object for [MediaCryptoMappingEntity].
 *
 * Facilitates CAS deduplication lookups, encryption envelope updates,
 * and upload worker synchronization status queries.
 * Filters active queries with `is_deleted = 0`.
 */
@Dao
interface MediaCryptoDao {

    @Upsert
    suspend fun upsert(crypto: MediaCryptoMappingEntity)

    @Upsert
    suspend fun upsertAll(cryptoList: List<MediaCryptoMappingEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(crypto: MediaCryptoMappingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mappings: List<MediaCryptoMappingEntity>)

    @Query("SELECT * FROM media_crypto_mapping WHERE is_deleted = 0")
    suspend fun getAll(): List<MediaCryptoMappingEntity>

    @Update
    suspend fun update(crypto: MediaCryptoMappingEntity)

    @Delete
    suspend fun delete(crypto: MediaCryptoMappingEntity)

    @Query("DELETE FROM media_crypto_mapping WHERE file_id = :fileId")
    suspend fun deleteByFileId(fileId: String): Int

    @Query("DELETE FROM media_crypto_mapping")
    suspend fun clearAll(): Int

    @Query("UPDATE media_crypto_mapping SET is_deleted = 1, lamport_clock = :lamportClock, device_id = :deviceId WHERE file_id = :fileId")
    suspend fun markDeleted(fileId: String, lamportClock: Long, deviceId: String): Int

    @Query("SELECT * FROM media_crypto_mapping WHERE file_id = :fileId AND is_deleted = 0")
    suspend fun getByFileId(fileId: String): MediaCryptoMappingEntity?

    @Query("SELECT * FROM media_crypto_mapping WHERE file_id = :fileId")
    suspend fun getAnyByFileId(fileId: String): MediaCryptoMappingEntity?

    @Query("SELECT * FROM media_crypto_mapping WHERE file_id = :fileId AND is_deleted = 0")
    fun observeByFileId(fileId: String): Flow<MediaCryptoMappingEntity?>

    @Query("SELECT * FROM media_crypto_mapping WHERE plaintext_sha256 = :plaintextSha256 AND is_deleted = 0 LIMIT 1")
    suspend fun getByPlaintextSha256(plaintextSha256: String): MediaCryptoMappingEntity?

    @Query("SELECT * FROM media_crypto_mapping WHERE plaintext_sha256 = :plaintextHash AND is_deleted = 0 LIMIT 1")
    suspend fun getByPlaintextHash(plaintextHash: String): MediaCryptoMappingEntity? = getByPlaintextSha256(plaintextHash)

    @Query("SELECT * FROM media_crypto_mapping WHERE ciphertext_hash = :ciphertextHash AND is_deleted = 0 LIMIT 1")
    suspend fun getByCiphertextHash(ciphertextHash: String): MediaCryptoMappingEntity?

    @Query("SELECT * FROM media_crypto_mapping WHERE upload_status = :status AND is_deleted = 0")
    suspend fun getByStatus(status: UploadStatus): List<MediaCryptoMappingEntity>

    @Query("SELECT * FROM media_crypto_mapping WHERE upload_status = :status AND is_deleted = 0")
    fun observeByStatus(status: UploadStatus): Flow<List<MediaCryptoMappingEntity>>

    @Query("SELECT * FROM media_crypto_mapping WHERE upload_status IN ('PENDING', 'FAILED', 'UPLOADING') AND is_deleted = 0")
    suspend fun getPendingOrFailedUploads(): List<MediaCryptoMappingEntity>

    @Query("SELECT * FROM media_crypto_mapping WHERE upload_status IN ('PENDING', 'FAILED', 'UPLOADING') AND is_deleted = 0")
    fun observePendingOrFailedUploads(): Flow<List<MediaCryptoMappingEntity>>

    @Query("SELECT * FROM media_crypto_mapping WHERE upload_status != 'SYNCED' AND is_deleted = 0")
    suspend fun getUnsyncedUploads(): List<MediaCryptoMappingEntity>

    @Query("SELECT * FROM media_crypto_mapping WHERE upload_status != 'SYNCED' AND is_deleted = 0")
    fun observeUnsyncedUploads(): Flow<List<MediaCryptoMappingEntity>>

    @Query("UPDATE media_crypto_mapping SET upload_status = 'PENDING' WHERE upload_status = 'UPLOADING'")
    suspend fun resetStuckUploads(): Int

    @Query("""
        UPDATE media_crypto_mapping 
        SET upload_status = :status, 
            ciphertext_hash = COALESCE(:ciphertextHash, ciphertext_hash),
            thumbnail_ciphertext_hash = COALESCE(:thumbnailCiphertextHash, thumbnail_ciphertext_hash),
            last_synced_at = COALESCE(:lastSyncedAt, last_synced_at),
            error_message = :errorMessage
        WHERE file_id = :fileId
    """)
    suspend fun updateUploadStatus(
        fileId: String,
        status: UploadStatus,
        ciphertextHash: String? = null,
        thumbnailCiphertextHash: String? = null,
        lastSyncedAt: Long? = null,
        errorMessage: String? = null
    ): Int
}
