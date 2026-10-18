package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wuming.android.tv.cloud.CloudCacheRecord

@Dao
@JvmSuppressWildcards
interface CloudCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(record: CloudCacheRecord): Long

    @Query("SELECT * FROM CloudCacheRecord WHERE provider = :provider AND state = 0 ORDER BY createdAt DESC")
    fun active(provider: String?): List<CloudCacheRecord>

    @Query(
        "SELECT * FROM CloudCacheRecord WHERE provider = :provider AND accountHash = :accountHash AND remoteFolderId = :folderId AND state = 0 ORDER BY createdAt DESC",
    )
    fun active(
        provider: String?,
        accountHash: String?,
        folderId: String?,
    ): List<CloudCacheRecord>

    @Query(
        "SELECT * FROM CloudCacheRecord WHERE provider = :provider AND accountHash = :accountHash AND remoteFolderId = :folderId ORDER BY createdAt DESC",
    )
    fun records(
        provider: String?,
        accountHash: String?,
        folderId: String?,
    ): List<CloudCacheRecord>

    @Query(
        "UPDATE CloudCacheRecord SET state = :state, updatedAt = :updatedAt WHERE provider = :provider AND accountHash = :accountHash AND remoteFileId = :fileId",
    )
    fun updateState(
        provider: String?,
        accountHash: String?,
        fileId: String?,
        state: Int,
        updatedAt: Long,
    ): Int
}
