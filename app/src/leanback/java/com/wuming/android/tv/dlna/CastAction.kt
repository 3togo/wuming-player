package com.wuming.android.tv.dlna

import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import com.wuming.android.tv.bean.Result

class CastAction @JvmOverloads constructor(
    currentURI: String?,
    currentURIMetaData: String?,
    headers: Map<String, String>?,
    val audioTrackFormat: String? = null
) : Parcelable {

    val currentURI: String = currentURI.orEmpty()
    val currentURIMetaData: String = currentURIMetaData.orEmpty()
    val headers: Map<String, String> = headers ?: emptyMap()

    private constructor(source: Parcel) : this(
        source.readString(),
        source.readString(),
        readHeaders(source),
        source.readString()
    )

    fun result(): Result {
        val result = Result()
        result.setUrl(currentURI)
        result.setHeader(headers)
        return result
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(currentURI)
        dest.writeString(currentURIMetaData)
        val bundle = Bundle()
        headers.forEach { (key, value) -> bundle.putString(key, value) }
        dest.writeBundle(bundle)
        dest.writeString(audioTrackFormat)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val KEY_EXTRA = "cast_action"

        @JvmField
        val CREATOR = object : Parcelable.Creator<CastAction> {
            override fun createFromParcel(source: Parcel): CastAction = CastAction(source)
            override fun newArray(size: Int): Array<CastAction?> = arrayOfNulls(size)
        }

        private fun readHeaders(source: Parcel): Map<String, String> {
            val bundle = source.readBundle(CastAction::class.java.classLoader) ?: return emptyMap()
            return bundle.keySet().associateWith { key -> bundle.getString(key).orEmpty() }
        }
    }
}
