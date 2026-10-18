package com.wuming.android.tv.bean

import android.os.Parcel
import android.os.Parcelable
import com.wuming.android.tv.impl.Diffable
import com.github.catvod.utils.Trans
import com.google.gson.annotations.SerializedName
import java.util.Objects

open class Value :
    Parcelable,
    Diffable<Value?> {
    @field:SerializedName("n")
    private var n: String? = null

    @field:SerializedName("v")
    private var v: String? = null

    @Transient
    private var activated = false

    private constructor(v: String?) {
        this.v = v
    }

    private constructor(n: String?, v: String?) {
        this.n = n
        this.v = v
    }

    protected constructor(source: Parcel) {
        n = source.readString()
        v = source.readString()
        activated = source.readByte().toInt() != 0
    }

    open fun getN(): String = BeanUtils.emptyIfNull(n)

    open fun getV(): String = BeanUtils.emptyIfNull(v)

    open fun setV(v: String?) {
        this.v = v
    }

    open fun isActivated(): Boolean = activated

    open fun setActivated(activated: Boolean) {
        this.activated = activated
    }

    open fun setActivated(item: Value?) {
        val equal = item!!.equals(this)
        activated = if (activated && equal) false else equal
    }

    open fun copy(): Value {
        val copy = Value(n, v)
        copy.activated = activated
        return copy
    }

    open fun trans(): Value {
        n = Trans.s2t(n)
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Value) return false
        return Objects.equals(getV(), other.getV())
    }

    override fun hashCode(): Int = Objects.hash(getV())

    override fun describeContents(): Int = 0

    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeString(n)
        dest.writeString(v)
        dest.writeByte(if (activated) 1 else 0)
    }

    override fun isSameItem(other: Value?): Boolean = equals(other)

    override fun isSameContent(other: Value?): Boolean = equals(other)

    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmStatic
        open fun create(v: String?): Value = Value(v)

        @JvmStatic
        open fun create(
            n: String?,
            v: String?,
        ): Value = Value(n, v).trans()

        @JvmField
        val CREATOR: Parcelable.Creator<Value> =
            object : Parcelable.Creator<Value> {
                override fun createFromParcel(source: Parcel): Value = Value(source)

                override fun newArray(size: Int): Array<Value?> = arrayOfNulls(size)
            }
    }
}
