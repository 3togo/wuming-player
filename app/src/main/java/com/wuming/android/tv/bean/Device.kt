package com.wuming.android.tv.bean

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import com.wuming.android.tv.App
import com.wuming.android.tv.ProductCore
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.db.AsyncDB
import com.wuming.android.tv.db.DataCache
import com.wuming.android.tv.impl.Diffable
import com.wuming.android.tv.server.Server
import com.wuming.android.tv.utils.UrlUtil
import com.wuming.android.tv.utils.Util
import java.util.Comparator
import java.util.Objects
import org.fourthline.cling.model.meta.RemoteDevice

@androidx.room.Entity(indices = [androidx.room.Index(value = ["uuid", "name"], unique = true)])
@JvmSuppressWildcards
open class Device :
    Diffable<Device>,
    Comparable<Device> {

    @PrimaryKey(autoGenerate = true)
    @SerializedName("id")
    private var id: Int? = null

    @SerializedName("uuid")
    @androidx.room.ColumnInfo(name = "uuid")
    private var databaseUuid: String? = null

    @SerializedName("name")
    @androidx.room.ColumnInfo(name = "name")
    private var databaseName: String? = null

    @SerializedName("ip")
    @androidx.room.ColumnInfo(name = "ip")
    private var databaseIp: String? = null

    @SerializedName("type")
    private var type: Int = 0

    @Ignore
    @SerializedName("serial")
    private var serial: String? = null

    @Ignore
    @SerializedName("eth")
    private var eth: String? = null

    @Ignore
    @SerializedName("wlan")
    private var wlan: String? = null

    @Ignore
    @SerializedName("time")
    private var time: Long = 0

    open fun getId(): Int? = id

    open fun setId(id: Int?) {
        this.id = id
    }

    // Match Room's nullable column type while preserving normalized persisted values.
    open fun getDatabaseUuid(): String? = getUuid()

    open fun setDatabaseUuid(value: String?) {
        setUuid(value)
    }

    open fun getUuid(): String = BeanUtils.emptyIfNull(databaseUuid)

    open fun setUuid(uuid: String?) {
        this.databaseUuid = uuid
    }

    // Match Room's nullable column type while preserving normalized persisted values.
    open fun getDatabaseName(): String? = getName()

    open fun setDatabaseName(value: String?) {
        setName(value)
    }

    open fun getName(): String = BeanUtils.emptyIfNull(databaseName)

    open fun setName(name: String?) {
        this.databaseName = name
    }

    // Match Room's nullable column type while preserving normalized persisted values.
    open fun getDatabaseIp(): String? = getIp()

    open fun setDatabaseIp(value: String?) {
        setIp(value)
    }

    open fun getIp(): String = BeanUtils.emptyIfNull(databaseIp)

    open fun setIp(ip: String?) {
        this.databaseIp = ip
    }

    open fun getType(): Int = type

    open fun setType(type: Int) {
        this.type = type
    }

    open fun setSerial(serial: String?) {
        this.serial = serial
    }

    open fun setEth(eth: String?) {
        this.eth = eth
    }

    open fun setWlan(wlan: String?) {
        this.wlan = wlan
    }

    open fun setTime(time: Long) {
        this.time = time
    }

    open fun isLeanback(): Boolean = getType() == 0

    open fun isMobile(): Boolean = getType() == 1

    open fun isDLNA(): Boolean = getType() == 2

    open fun isChromecast(): Boolean = getType() == 3

    open fun isApp(): Boolean = isLeanback() || isMobile()

    open fun getHost(): String = if (isDLNA()) getUuid() else UrlUtil.host(getIp())

    open fun save(): Device {
        if (DataCache.isReady()) {
            DataCache.get().putDevice(this)
        } else {
            AppDatabase.get().getDeviceDao().insertOrUpdate(this)
        }
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Device) return false

        return getUuid() == other.getUuid()
    }

    override fun hashCode(): Int = java.util.Objects.hash(getUuid())

    override fun toString(): String = App.gson().toJson(this)

    public override fun isSameItem(other: Device): Boolean = equals(other)

    public override fun isSameContent(other: Device): Boolean =
        getName() == other.getName() && getType() == other.getType()

    override fun compareTo(other: Device): Int = Comparator.comparingInt<Device> { it.getType() }
        .thenComparing({ it.getName() }, String.CASE_INSENSITIVE_ORDER)
        .thenComparing { it.getUuid() }.compare(this, other)

    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmStatic
        open fun get(): Device {
            val device: Device = Device()
            device.setTime(App.time())
            device.setSerial(Util.getSerial())
            device.setEth(Util.getMac("eth0"))
            device.setWlan(Util.getMac("wlan0"))
            device.setUuid(Util.getAndroidId())
            device.setName(Util.getDeviceName())
            device.setIp(Server.get().getAddress())
            device.setType(ProductCore.getDeviceType())
            return device
        }

        @JvmStatic
        open fun get(item: RemoteDevice): Device {
            com.wuming.android.tv.cast.RendererCapabilityProfiles.discovered(item)
            val device: Device = Device()
            device.setUuid(item.getIdentity().getUdn().getIdentifierString())
            device.setName(item.getDetails().getFriendlyName())
            device.setType(2)
            // Extract IP address from RemoteDevice identity
            if (item.getIdentity().getDescriptorURL() != null) {
                device.setIp(item.getIdentity().getDescriptorURL().getHost())
            }
            return device
        }

        @JvmStatic
        open fun objectFrom(str: String?): Device? =
            App.gson().fromJson<Device?>(str, Device::class.java)

        @JvmStatic
        open fun getAll(): List<Device> {
            if (DataCache.isReady()) return DataCache.get().getDevices()
            return AppDatabase.get().getDeviceDao().findAll()
        }

        @JvmStatic
        open fun delete() {
            if (DataCache.isReady()) {
                DataCache.get().clearDevices()
            } else {
                AppDatabase.get().getDeviceDao().delete()
            }
        }

        // Async versions for UI thread safety
        @JvmStatic
        open fun getAllAsync(callback: java.util.function.Consumer<List<Device>>?) {
            AsyncDB.query({ Device.Companion.getAll() }, callback)
        }

        @JvmStatic
        open fun deleteAsync() {
            AsyncDB.execute({ Device.Companion.delete() })
        }
    }
}
