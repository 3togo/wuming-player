package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.wuming.android.tv.bean.Live

@Dao
@JvmSuppressWildcards
abstract class LiveDao : BaseDao<Live>() {
    @Query("SELECT * FROM Live")
    abstract fun findAll(): List<Live>

    @Query("SELECT * FROM Live WHERE name = :name")
    abstract fun find(name: String?): Live?
}
