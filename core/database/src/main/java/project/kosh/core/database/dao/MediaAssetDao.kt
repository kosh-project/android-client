package project.kosh.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import project.kosh.core.database.entity.MediaAssetEntity
import project.kosh.core.database.model.MediaWithCryptoDetails

/**
 * Data Access Object for [MediaAssetEntity].
 *
 * Provides reactive Kotlin [Flow] streams and keyset-paginated queries optimized for
 * Android photo gallery feeds and offline-first synchronization.
 * Active queries filter out tombstoned items with `WHERE is_deleted = 0`.
 */
@Dao
interface MediaAssetDao {

    @Upsert
    suspend fun upsert(asset: MediaAssetEntity)

    @Upsert
    suspend fun upsertAll(assets: List<MediaAssetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(asset: MediaAssetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(assets: List<MediaAssetEntity>)

    @Update
    suspend fun update(asset: MediaAssetEntity)

    @Delete
    suspend fun delete(asset: MediaAssetEntity)

    @Query("DELETE FROM media_assets WHERE file_id = :fileId")
    suspend fun deleteById(fileId: String): Int

    @Query("DELETE FROM media_assets WHERE file_id IN (:fileIds)")
    suspend fun deleteByIds(fileIds: List<String>): Int

    @Query("DELETE FROM media_assets")
    suspend fun clearAll(): Int

    @Query("UPDATE media_assets SET is_deleted = 1, lamport_clock = :lamportClock, device_id = :deviceId, updated_at = :updatedAt WHERE file_id = :fileId")
    suspend fun markDeleted(
        fileId: String,
        lamportClock: Long,
        deviceId: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("SELECT * FROM media_assets WHERE file_id = :fileId AND is_deleted = 0")
    suspend fun getAssetById(fileId: String): MediaAssetEntity?

    @Query("SELECT * FROM media_assets WHERE file_id = :fileId")
    suspend fun getAnyAssetById(fileId: String): MediaAssetEntity?

    @Query("SELECT * FROM media_assets WHERE file_id = :fileId AND is_deleted = 0")
    fun observeAssetById(fileId: String): Flow<MediaAssetEntity?>

    @Query("SELECT * FROM media_assets WHERE is_deleted = 0 ORDER BY captured_at DESC, file_id DESC")
    suspend fun getAllAssets(): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE is_deleted = 0 ORDER BY captured_at DESC, file_id DESC")
    fun observeAllAssets(): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE is_deleted = 0 ORDER BY captured_at DESC, file_id DESC LIMIT :limit OFFSET :offset")
    suspend fun getAssetsPaged(limit: Int, offset: Int): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE is_deleted = 0 AND captured_at < :beforeCapturedAt ORDER BY captured_at DESC, file_id DESC LIMIT :limit")
    suspend fun getAssetsCapturedBefore(beforeCapturedAt: Long, limit: Int): List<MediaAssetEntity>

    @Query("""
        SELECT * FROM media_assets 
        WHERE is_deleted = 0 AND ((:beforeCapturedAt IS NULL) OR (captured_at < :beforeCapturedAt) OR (captured_at = :beforeCapturedAt AND file_id < :beforeFileId))
        ORDER BY captured_at DESC, file_id DESC 
        LIMIT :limit
    """)
    suspend fun getAssetsBeforeCursor(beforeCapturedAt: Long?, beforeFileId: String?, limit: Int): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE is_deleted = 0 AND media_type LIKE :typePrefix || '%' ORDER BY captured_at DESC, file_id DESC")
    fun observeAssetsByType(typePrefix: String): Flow<List<MediaAssetEntity>>

    @Query("SELECT * FROM media_assets WHERE is_deleted = 0 AND media_type LIKE :typePrefix || '%' ORDER BY captured_at DESC, file_id DESC")
    suspend fun getAssetsByType(typePrefix: String): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE is_deleted = 0 AND captured_at BETWEEN :startTime AND :endTime ORDER BY captured_at DESC, file_id DESC")
    suspend fun getAssetsCapturedBetween(startTime: Long, endTime: Long): List<MediaAssetEntity>

    @Query("SELECT * FROM media_assets WHERE is_deleted = 0 AND captured_at BETWEEN :startTime AND :endTime ORDER BY captured_at DESC, file_id DESC")
    fun observeAssetsCapturedBetween(startTime: Long, endTime: Long): Flow<List<MediaAssetEntity>>

    @Query("UPDATE media_assets SET thumbnail_hash = :thumbnailHash, updated_at = :updatedAt WHERE file_id = :fileId")
    suspend fun updateThumbnailHash(
        fileId: String,
        thumbnailHash: String,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("SELECT COUNT(*) FROM media_assets WHERE is_deleted = 0")
    suspend fun getAssetCount(): Int

    @Query("SELECT COUNT(*) FROM media_assets WHERE is_deleted = 0")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM media_assets WHERE is_deleted = 0")
    fun observeAssetCount(): Flow<Int>

    @Transaction
    @Query("SELECT * FROM media_assets WHERE file_id = :fileId AND is_deleted = 0")
    suspend fun getMediaWithCryptoById(fileId: String): MediaWithCryptoDetails?

    @Transaction
    @Query("SELECT * FROM media_assets WHERE file_id = :fileId AND is_deleted = 0")
    fun observeMediaWithCryptoById(fileId: String): Flow<MediaWithCryptoDetails?>

    @Transaction
    @Query("SELECT * FROM media_assets WHERE is_deleted = 0 ORDER BY captured_at DESC, file_id DESC")
    fun observeAllMediaWithCrypto(): Flow<List<MediaWithCryptoDetails>>
}
