package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.wuming.android.tv.bean.Keep

@Dao
@JvmSuppressWildcards
abstract class KeepDao : BaseDao<Keep>() {
    @Query("SELECT * FROM Keep")
    abstract fun findAll(): List<Keep>

    @Query("SELECT * FROM Keep WHERE type = 0 ORDER BY createTime DESC")
    abstract fun getVod(): List<Keep>

    @Query("SELECT * FROM Keep WHERE type = 1 ORDER BY createTime DESC")
    abstract fun getLive(): List<Keep>

    @Query("SELECT * FROM Keep WHERE type = 0 AND cid = :cid AND `key` = :key")
    abstract fun find(
        cid: Int,
        key: String?,
    ): Keep?

    @Query("SELECT * FROM Keep WHERE type = 1 AND `key` = :key")
    abstract fun find(key: String?): Keep?

    @Query("DELETE FROM Keep WHERE type = 1 AND `key` = :key")
    abstract fun delete(key: String?): Unit

    @Query("DELETE FROM Keep WHERE type = 0 AND cid = :cid AND `key` = :key")
    abstract fun delete(
        cid: Int,
        key: String?,
    ): Unit

    @Query("DELETE FROM Keep WHERE type = 0 AND cid = :cid")
    abstract fun delete(cid: Int): Unit

    @Query("DELETE FROM Keep WHERE type = 0")
    abstract fun delete(): Unit

    @Query("DELETE FROM Keep")
    abstract fun deleteAll(): Unit
}
