package com.wuming.android.tv.cloud.opendrive

import com.google.gson.JsonObject

internal object ExternalEpisodePayload {
    const val MAX_LENGTH = com.wuming.tv.core.cloud.ExternalEpisodePayload.MAX_LENGTH
    @JvmStatic fun json(payload: String?, decodeBase64: Boolean): JsonObject? = com.wuming.tv.core.cloud.ExternalEpisodePayload.json(payload, decodeBase64)
    @JvmStatic fun belongsTo(flag: String?, payload: JsonObject?, vararg markers: String): Boolean = com.wuming.tv.core.cloud.ExternalEpisodePayload.belongsTo(flag, payload, *markers)
    @JvmStatic fun labeledProviderId(flag: String?): String? = com.wuming.tv.core.cloud.ExternalEpisodePayload.labeledProviderId(flag)
    @JvmStatic fun first(value: JsonObject?, vararg keys: String): String = com.wuming.tv.core.cloud.ExternalEpisodePayload.first(value, *keys)
    @JvmStatic fun number(value: JsonObject?, vararg keys: String): Long = com.wuming.tv.core.cloud.ExternalEpisodePayload.number(value, *keys)
    @JvmStatic fun identifier(value: String?): Boolean = com.wuming.tv.core.cloud.ExternalEpisodePayload.identifier(value)
    @JvmStatic fun capability(value: String?): Boolean = com.wuming.tv.core.cloud.ExternalEpisodePayload.capability(value)
    @JvmStatic fun split(payload: String?, delimiter: String, minimumParts: Int): Array<String>? = com.wuming.tv.core.cloud.ExternalEpisodePayload.split(payload, delimiter, minimumParts)
}
