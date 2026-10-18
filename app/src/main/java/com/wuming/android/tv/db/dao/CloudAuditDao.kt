package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.wuming.android.tv.cloud.CloudAuditEvent

@Dao
@JvmSuppressWildcards
interface CloudAuditDao {
    @Insert
    fun insert(event: CloudAuditEvent): Long

    @Query("SELECT * FROM CloudAuditEvent WHERE provider = :provider ORDER BY createdAt DESC LIMIT :limit")
    fun recent(
        provider: String?,
        limit: Int,
    ): List<CloudAuditEvent>
}
