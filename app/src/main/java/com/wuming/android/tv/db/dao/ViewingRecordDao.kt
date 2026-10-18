package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.wuming.android.tv.bean.ViewingRecord

@Dao
@JvmSuppressWildcards
abstract class ViewingRecordDao : BaseDao<ViewingRecord>() {
    @Query("SELECT * FROM ViewingRecord ORDER BY lastWatchedAt DESC LIMIT :limit")
    abstract fun findRecent(limit: Int): List<ViewingRecord>

    @Query("SELECT * FROM ViewingRecord WHERE lastWatchedAt >= :since ORDER BY lastWatchedAt DESC LIMIT :limit")
    abstract fun findSince(
        since: Long,
        limit: Int,
    ): List<ViewingRecord>

    @Query("DELETE FROM ViewingRecord")
    abstract fun delete(): Unit

    @Query("DELETE FROM ViewingRecord WHERE cid = :cid")
    abstract fun delete(cid: Int): Unit
}
