package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.wuming.android.tv.bean.EpisodeNameLog

@Dao
abstract class EpisodeNameLogDao : BaseDao<EpisodeNameLog>() {

    @Query("SELECT * FROM EpisodeNameLog WHERE isLong = 1 ORDER BY timestamp DESC")
    abstract fun getLongNames(): List<EpisodeNameLog>

    @Query("SELECT * FROM EpisodeNameLog ORDER BY timestamp DESC LIMIT :limit")
    abstract fun getRecent(limit: Int): List<EpisodeNameLog>

    @Query("SELECT COUNT(*) FROM EpisodeNameLog WHERE isLong = 1")
    abstract fun getLongNameCount(): Int

    @Query("DELETE FROM EpisodeNameLog WHERE timestamp < :before")
    abstract fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM EpisodeNameLog WHERE isLong = 0 AND id NOT IN (SELECT id FROM EpisodeNameLog WHERE isLong = 0 ORDER BY timestamp DESC LIMIT :keep)")
    abstract fun trimSampledLogs(keep: Int): Int
}
