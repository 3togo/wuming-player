package com.wuming.android.tv.bean

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.db.AsyncDB
import com.wuming.android.tv.db.DataCache
import java.util.function.Consumer

@Entity(tableName = "drive")
open class Drive {
    @field:PrimaryKey(autoGenerate = true)
    open var id: Int = 0

    @JvmField
    @field:ColumnInfo(name = "name")
    var name: String? = null

    @JvmField
    @field:ColumnInfo(name = "type")
    var type: Int = 0

    @JvmField
    @field:ColumnInfo(name = "configJson")
    var configJson: String? = null

    open fun getName(): String = BeanUtils.emptyIfNull(name)

    open fun setName(name: String?) {
        this.name = name
    }

    open fun getType(): Int = type

    open fun setType(type: Int) {
        this.type = type
    }

    open fun getConfigJson(): String = BeanUtils.emptyIfNull(configJson)

    open fun setConfigJson(configJson: String?) {
        this.configJson = configJson
    }

    companion object {
        @JvmStatic fun getAll(): List<Drive> = if (DataCache.isReady()) DataCache.get().getDrives() else AppDatabase.get().getDriveDao().getAll()

        @JvmStatic fun delete(id: Int) {
            if (DataCache.isReady()) DataCache.get().removeDrive(id) else AppDatabase.get().getDriveDao().delete(id)
        }

        @JvmStatic fun insert(drive: Drive) {
            if (DataCache.isReady()) DataCache.get().putDrive(drive) else AppDatabase.get().getDriveDao().insert(drive)
        }

        @JvmStatic fun update(drive: Drive) {
            if (DataCache.isReady()) DataCache.get().updateDrive(drive) else AppDatabase.get().getDriveDao().update(drive)
        }

        @JvmStatic fun getAllAsync(callback: Consumer<List<Drive>>?) {
            AsyncDB.query({ getAll() }, callback)
        }

        @JvmStatic fun deleteAsync(id: Int) {
            AsyncDB.execute { AppDatabase.get().getDriveDao().delete(id) }
        }

        @JvmStatic fun insertAsync(drive: Drive) {
            AsyncDB.execute { AppDatabase.get().getDriveDao().insert(drive) }
        }

        @JvmStatic fun updateAsync(drive: Drive) {
            AsyncDB.execute { AppDatabase.get().getDriveDao().update(drive) }
        }
    }
}
