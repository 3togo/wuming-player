@file:Suppress("ktlint:standard:function-naming") // Preserve the existing protected Java DAO method names.

package com.wuming.android.tv.db.dao
import android.util.Log
import androidx.room.Dao
import androidx.room.Query
import com.wuming.android.tv.bean.Config

@Dao
abstract class ConfigDao : BaseDao<Config>() {
    @Query("SELECT * FROM Config")
    abstract fun findAll(): List<Config>

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC")
    open fun findByType(type: Int): List<Config> {
        val items = _findByType(type)
        Log.d("ConfigDao", "findByType: type=$type, count=${items.size}")
        for (item in items) {
            Log.d(
                "ConfigDao",
                "  findByType item: id=${item.id}, type=${item.type}, name='${item.name}', url='${item.url}'",
            )
        }
        return items
    }

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC")
    protected abstract fun _findByType(type: Int): List<Config>

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC")
    abstract fun findUrlByType(type: Int): List<Config>

    @Query("SELECT * FROM Config WHERE id = :id")
    abstract fun findById(id: Int): Config?

    @Query("SELECT * FROM Config WHERE type = :type ORDER BY time DESC LIMIT 1")
    abstract fun findOne(type: Int): Config?

    @Query("SELECT * FROM Config WHERE url = :url AND type = :type")
    open fun find(
        url: String?,
        type: Int,
    ): Config? {
        val item = _find(url, type)
        Log.d(
            "ConfigDao",
            "find: url='$url', type=$type, found=${item != null}" + if (item != null) ", id=${item.id}, name='${item.name}'" else "",
        )
        return item
    }

    @Query("SELECT * FROM Config WHERE url = :url AND type = :type")
    protected abstract fun _find(
        url: String?,
        type: Int,
    ): Config?

    @Query("DELETE FROM Config WHERE url = :url AND type = :type")
    abstract fun delete(
        url: String?,
        type: Int,
    )

    @Query("DELETE FROM Config WHERE url = :url")
    abstract fun delete(url: String?)
}
