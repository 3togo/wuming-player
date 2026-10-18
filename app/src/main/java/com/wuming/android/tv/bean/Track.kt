package com.wuming.android.tv.bean

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.db.AsyncDB
import com.wuming.android.tv.db.DataCache
import com.google.gson.annotations.SerializedName
import java.util.function.Consumer

@Entity(indices = [Index(value = ["key", "type"], unique = true)])
open class Track(
    type: Int,
    name: String?,
    format: String?,
) {
    @field:PrimaryKey(autoGenerate = true)
    open var id: Int = 0
    open var type: Int = type
    open var key: String? = null
    open var name: String? = name
    open var format: String? = format

    @field:ColumnInfo(name = "selected")
    @field:SerializedName("selected")
    open var isSelected: Boolean = false

    open fun key(key: String?): Track {
        this.key = key
        return this
    }

    open fun toggle(): Track {
        isSelected = !isSelected
        return this
    }

    open fun save(): Track {
        if (BeanUtils.isEmpty(key)) return this
        if (DataCache.isReady()) DataCache.get().putTrack(this) else AppDatabase.get().getTrackDao().insert(this)
        return this
    }

    companion object {
        @JvmStatic fun find(key: String?): List<Track> {
            if (BeanUtils.isEmpty(key)) return emptyList()
            return if (DataCache.isReady()) DataCache.get().findTracks(key) else AppDatabase.get().getTrackDao().find(key)
        }

        @JvmStatic fun delete(key: String?) {
            if (BeanUtils.isEmpty(key)) return
            if (DataCache.isReady()) DataCache.get().removeTracks(key) else AppDatabase.get().getTrackDao().delete(key)
        }

        @JvmStatic fun findAsync(
            key: String?,
            callback: Consumer<List<Track>>?,
        ) {
            AsyncDB.query({ find(key) }, callback)
        }

        @JvmStatic fun deleteAsync(key: String?) {
            if (BeanUtils.isEmpty(key)) return
            AsyncDB.execute { AppDatabase.get().getTrackDao().delete(key) }
        }
    }
}
