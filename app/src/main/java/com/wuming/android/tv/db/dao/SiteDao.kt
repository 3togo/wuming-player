package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.wuming.android.tv.bean.Site

@Dao
@JvmSuppressWildcards
abstract class SiteDao : BaseDao<Site>() {
    @Query("SELECT * FROM Site")
    abstract fun findAll(): List<Site>
}
