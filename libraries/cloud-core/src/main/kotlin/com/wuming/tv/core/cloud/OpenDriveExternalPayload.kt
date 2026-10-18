package com.wuming.tv.core.cloud

import com.google.gson.JsonObject

class OpenDriveExternalPayload private constructor(
    @get:JvmName("flag") val flag: String,
    @get:JvmName("raw") val raw: String
) {
    private val directJson: JsonObject? = ExternalEpisodePayload.json(raw, false)
    private var decoded = false
    private var decodedJson: JsonObject? = null

    @Synchronized
    fun json(decodeBase64: Boolean): JsonObject? {
        if (directJson != null || !decodeBase64) return directJson
        if (!decoded) {
            decodedJson = ExternalEpisodePayload.json(raw, true)
            decoded = true
        }
        return decodedJson
    }

    companion object {
        @JvmStatic
        fun create(flag: String?, raw: String?): OpenDriveExternalPayload? {
            if (raw.isNullOrBlank() || raw.length > ExternalEpisodePayload.MAX_LENGTH) return null
            return OpenDriveExternalPayload(flag ?: "", raw)
        }
    }
}
