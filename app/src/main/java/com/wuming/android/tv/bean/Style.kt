package com.wuming.android.tv.bean

import android.os.Parcel
import android.os.Parcelable
import com.wuming.android.tv.ui.base.ViewType
import com.wuming.android.tv.utils.LayoutConfig
import com.google.gson.annotations.SerializedName

class Style : Parcelable {

    @SerializedName("type")
    private var type: String? = null

    @SerializedName("ratio")
    private var ratio: Float = 0f

    private var land: Boolean = false

    constructor(type: String) {
        this.type = type
    }

    constructor(type: String, ratio: Float) {
        this.type = type
        this.ratio = ratio
    }

    constructor(type: String, ratio: Float, land: Boolean) {
        this.type = type
        this.ratio = ratio
        this.land = land
    }

    protected constructor(`in`: Parcel) {
        this.type = `in`.readString()
        this.ratio = `in`.readFloat()
        this.land = `in`.readByte().toInt() != 0
    }

    fun getType(): String = if (BeanUtils.emptyIfNull(type).isEmpty()) "rect" else type!!

    fun getRatio(): Float = LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO

    fun isRect(): Boolean = "rect" == getType()

    fun isOval(): Boolean = "oval" == getType()

    fun isList(): Boolean = "list" == getType()

    fun isLand(): Boolean = land

    fun getViewType(): Int = when (getType()) {
        "oval" -> ViewType.OVAL
        "list" -> ViewType.LIST
        else -> ViewType.RECT
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Style) return false
        return getType() == other.getType() && getRatio() == other.getRatio() && land == other.land
    }

    override fun hashCode(): Int = java.util.Objects.hash(getType(), getRatio(), land)

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(type)
        dest.writeFloat(ratio)
        dest.writeByte(if (land) 1.toByte() else 0.toByte())
    }

    companion object {

        @JvmStatic
        fun rect(): Style = Style("rect", LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO)

        @JvmStatic
        fun list(): Style = Style("list")

        @JvmStatic
        fun get(land: Int, circle: Int, ratio: Float): Style? {
            if (land == 1) return Style("rect", LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO, true)
            if (circle == 1) return Style("oval", LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO)
            return null
        }

        @JvmField
        val CREATOR: Parcelable.Creator<Style> = object : Parcelable.Creator<Style> {
            override fun createFromParcel(source: Parcel): Style = Style(source)
            override fun newArray(size: Int): Array<Style?> = arrayOfNulls(size)
        }
    }
}
