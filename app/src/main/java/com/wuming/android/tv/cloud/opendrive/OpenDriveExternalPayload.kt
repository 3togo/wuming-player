package com.wuming.android.tv.cloud.opendrive

import com.google.gson.JsonObject

class OpenDriveExternalPayload private constructor(private val shared: com.wuming.tv.core.cloud.OpenDriveExternalPayload) {
    @get:JvmName("flag") val flag: String get() = shared.flag
    @get:JvmName("raw") val raw: String get() = shared.raw
    fun json(decodeBase64: Boolean): JsonObject? = shared.json(decodeBase64)
    fun corePayload(): com.wuming.tv.core.cloud.OpenDriveExternalPayload = shared
    companion object {
        @JvmStatic fun create(flag: String?, raw: String?): OpenDriveExternalPayload? =
            com.wuming.tv.core.cloud.OpenDriveExternalPayload.create(flag, raw)?.let(::OpenDriveExternalPayload)
    }
}
