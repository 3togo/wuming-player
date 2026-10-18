package com.wuming.android.tv.bean

import android.os.Parcel
import android.os.Parcelable
import android.os.Parcelable.Creator
import androidx.annotation.Nullable
import com.wuming.android.tv.App
import com.wuming.android.tv.impl.Diffable
import com.github.catvod.utils.Trans
import com.google.gson.annotations.SerializedName
import org.simpleframework.xml.Attribute
import org.simpleframework.xml.Root
import org.simpleframework.xml.Text
import java.util.Objects

@Root(strict = false)
class Class : Parcelable, Diffable<Class> {

    @Attribute(name = "id", required = false)
    @SerializedName(value = "type_id", alternate = ["id"])
    private var typeId: String? = null

    @Text
    @SerializedName(value = "type_name", alternate = ["name"])
    private var typeName: String? = null

    @SerializedName("type_flag")
    private var typeFlag: String? = null

    @SerializedName("filters")
    private var filters: List<Filter>? = null

    @SerializedName("land")
    private var land: Int? = null

    @SerializedName("circle")
    private var circle: Int? = null

    @SerializedName("ratio")
    private var ratio: Float? = null

    private var filter: Boolean = false
    private var activated: Boolean = false

    constructor()

    protected constructor(`in`: Parcel) {
        typeId = `in`.readString()
        typeName = `in`.readString()
        typeFlag = `in`.readString()
        filter = `in`.readByte().toInt() != 0
        land = `in`.readValue(Int::class.java.classLoader) as Int?
        circle = `in`.readValue(Int::class.java.classLoader) as Int?
        ratio = `in`.readValue(Float::class.java.classLoader) as Float?
        activated = `in`.readByte().toInt() != 0
    }

    fun getTypeId(): String = BeanUtils.emptyIfNull(typeId)

    fun setTypeId(typeId: String?) { this.typeId = typeId }

    fun getTypeName(): String = BeanUtils.emptyIfNull(typeName)

    fun setTypeName(typeName: String?) { this.typeName = typeName }

    fun getTypeFlag(): String = BeanUtils.emptyIfNull(typeFlag)

    fun setTypeFlag(typeFlag: String?) { this.typeFlag = typeFlag }

    fun getFilters(): List<Filter> = BeanUtils.emptyListIfNull(filters)

    fun setFilters(filters: List<Filter>?) { this.filters = filters }

    fun getLand(): Int = BeanUtils.getOrDefault(land, 0)

    fun getCircle(): Int = BeanUtils.getOrDefault(circle, 0)

    fun getRatio(): Float = BeanUtils.getOrDefault(ratio, 0f)

    fun getFilter(): Boolean = filter

    fun setFilter(filter: Boolean) { this.filter = filter }

    fun isActivated(): Boolean = activated

    fun setActivated(activated: Boolean) { this.activated = activated }

    fun isHome(): Boolean = "home" == getTypeId()

    fun isFolder(): Boolean = "1" == getTypeFlag()

    fun trans() {
        if (Trans.pass()) return
        typeName = Trans.s2t(typeName)
    }

    fun getStyle(): Style = Style.get(getLand(), getCircle(), getRatio()) ?: Style.rect()

    override fun equals(@Nullable other: Any?): Boolean {
        if (this === other) return true
        if (other !is Class) return false
        return Objects.equals(getTypeId(), other.getTypeId())
    }

    override fun hashCode(): Int = Objects.hash(getTypeId())

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(typeId)
        dest.writeString(typeName)
        dest.writeString(typeFlag)
        dest.writeByte(if (filter) 1 else 0)
        dest.writeValue(land)
        dest.writeValue(circle)
        dest.writeValue(ratio)
        dest.writeByte(if (activated) 1 else 0)
    }

    override fun isSameItem(other: Class): Boolean = equals(other)

    override fun isSameContent(other: Class): Boolean =
        getTypeName() == other.getTypeName() && getTypeFlag() == other.getTypeFlag()

    companion object {
        @JvmStatic
        fun objectFrom(json: String): Class = App.gson().fromJson(json, Class::class.java)

        @JvmField
        val CREATOR: Creator<Class> = object : Creator<Class> {
            override fun createFromParcel(source: Parcel): Class = Class(source)
            override fun newArray(size: Int): Array<Class?> = arrayOfNulls(size)
        }
    }
}
