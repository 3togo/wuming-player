package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wuming.android.tv.bean.Track

@Dao
@JvmSuppressWildcards
abstract class TrackDao : BaseDao<Track>() {
    @Query("SELECT * FROM Track WHERE `key` = :key")
    abstract fun find(key: String?): List<Track>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract override fun insert(item: Track): Long?

    @Query("DELETE FROM Track WHERE `key` = :key")
    abstract fun delete(key: String?): Unit
}
