package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.wuming.android.tv.bean.History

@Dao
@JvmSuppressWildcards
abstract class HistoryDao : BaseDao<History>() {
    @Query("SELECT * FROM History")
    abstract fun findAll(): List<History>

    @Query("SELECT * FROM History WHERE cid = :cid AND createTime >= :createTime ORDER BY createTime DESC LIMIT 60")
    abstract fun find(
        cid: Int,
        createTime: Long,
    ): List<History>

    @Query("SELECT * FROM History WHERE createTime >= :createTime ORDER BY createTime DESC LIMIT 60")
    abstract fun findRecent(createTime: Long): List<History>

    @Query("SELECT * FROM History ORDER BY createTime DESC LIMIT :limit")
    abstract fun findRecentLimit(limit: Int): List<History>

    @Query("SELECT * FROM History WHERE cid = :cid AND `key` = :key")
    abstract fun find(
        cid: Int,
        key: String?,
    ): History?

    @Query("SELECT * FROM History WHERE `key` = :key")
    abstract fun findByKey(key: String?): History?

    @Query("SELECT * FROM History WHERE cid = :cid AND vodName = :vodName ORDER BY createTime DESC")
    abstract fun findByName(
        cid: Int,
        vodName: String?,
    ): List<History>

    @Query("SELECT * FROM History WHERE vodName = :vodName ORDER BY createTime DESC")
    abstract fun findByNameAll(vodName: String?): List<History>

    @Query("DELETE FROM History WHERE cid = :cid AND `key` = :key")
    abstract fun delete(
        cid: Int,
        key: String?,
    ): Unit

    @Query("DELETE FROM History WHERE cid = :cid")
    abstract fun delete(cid: Int): Unit

    @Query("DELETE FROM History")
    abstract fun delete(): Unit
}
