package com.wuming.android.tv.bean

import android.os.Parcel
import android.os.Parcelable
import com.google.gson.annotations.SerializedName

open class Cate protected constructor(
    source: Parcel,
) : Parcelable {
    @field:SerializedName("land")
    private var land: Int? = source.readValue(Int::class.javaObjectType.classLoader) as Int?

    @field:SerializedName("circle")
    private var circle: Int? = source.readValue(Int::class.javaObjectType.classLoader) as Int?

    @field:SerializedName("ratio")
    private var ratio: Float? = source.readValue(Float::class.javaObjectType.classLoader) as Float?

    open fun getLand(): Int = land ?: 0

    open fun getCircle(): Int = circle ?: 0

    open fun getRatio(): Float = ratio ?: 0f

    open fun getStyle(): Style = Style.get(getLand(), getCircle(), getRatio())!!

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeValue(land)
        dest.writeValue(circle)
        dest.writeValue(ratio)
    }

    companion object {
        @JvmField val CREATOR: Parcelable.Creator<Cate> =
            object : Parcelable.Creator<Cate> {
                override fun createFromParcel(source: Parcel): Cate = Cate(source)

                override fun newArray(size: Int): Array<Cate?> = arrayOfNulls(size)
            }
    }
}
