package com.wuming.android.tv.bean

import androidx.media3.common.C
import com.wuming.android.tv.App
import com.wuming.android.tv.gson.HeaderAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import java.util.UUID

class Drm private constructor(
    @SerializedName("key") private var key: String?,
    @SerializedName("type") private var type: String?,
    @SerializedName("header") @JsonAdapter(HeaderAdapter::class) private var header: Map<String, String>?,
    @SerializedName("forceKey") private var forceKey: Boolean
) {
    fun getKey(): String = BeanUtils.emptyIfNull(key)

    fun getType(): String = BeanUtils.emptyIfNull(type)

    fun isForceKey(): Boolean = forceKey

    fun getHeader(): Map<String, String> = BeanUtils.emptyMapIfNull(header)

    fun getUUID(): UUID {
        if (getType().contains("playready")) return C.PLAYREADY_UUID
        if (getType().contains("widevine")) return C.WIDEVINE_UUID
        if (getType().contains("clearkey")) return C.CLEARKEY_UUID
        return C.UUID_NIL
    }

    override fun toString(): String = App.gson().toJson(this)

    companion object {
        @JvmStatic
        fun create(key: String?, type: String?, header: Map<String, String>?, forceKey: Boolean): Drm =
            Drm(key, type, header, forceKey)
    }
}
