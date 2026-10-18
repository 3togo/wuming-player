package com.wuming.android.tv.db.dao
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update

@Dao
@JvmSuppressWildcards
abstract class BaseDao<T> {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insert(item: T): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insert(items: List<T>): List<Long>

    @Update(onConflict = OnConflictStrategy.REPLACE)
    abstract fun update(item: T)

    @Update abstract fun update(items: List<T>)

    open fun insertOrUpdate(item: T) {
        if (insert(item)!! == -1L) update(item)
    }

    open fun insertOrUpdate(items: List<T>) {
        if (items.isEmpty()) return
        val result = insert(items)
        val list = ArrayList<T>()
        for (i in result.indices) if (result[i] == -1L) list.add(items[i])
        if (list.isNotEmpty()) update(list)
    }
}
