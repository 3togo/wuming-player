package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.wuming.android.tv.bean.Device

@Dao
@JvmSuppressWildcards
abstract class DeviceDao : BaseDao<Device>() {
    @Query("SELECT * FROM Device")
    abstract fun findAll(): List<Device>

    @Query("DELETE FROM Device")
    abstract fun delete(): Unit
}
