package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import com.wuming.android.tv.bean.Drive

@Dao
@JvmSuppressWildcards
abstract class DriveDao : BaseDao<Drive>() {
    @Query("SELECT * FROM drive")
    abstract fun getAll(): List<Drive>

    @Query("SELECT * FROM drive WHERE id = :id")
    abstract fun getById(id: Int): Drive?

    @Delete
    abstract fun delete(drive: Drive): Unit

    @Query("DELETE FROM drive WHERE id = :id")
    abstract fun delete(id: Int): Unit

    @Query("DELETE FROM drive")
    abstract fun clear(): Unit
}
