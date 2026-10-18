package com.wuming.tv.core.playback

import com.google.gson.Gson
import com.google.gson.JsonParser

/** Reject incomplete or historically obfuscated reports without replacing stored evidence. */
object HardwareCapabilityJson {
    @JvmStatic fun decode(json: String?): HardwareCapabilityReport? = runCatching {
        if (json == null) return null
        val root = JsonParser.parseString(json).asJsonObject
        root.get("detectedAt").asLong
        root.get("device").asString
        root.getAsJsonArray("hdrTypes").forEach { it.asInt }
        root.getAsJsonArray("decoders").forEach { value ->
            val codec = value.asJsonObject
            codec.get("name").asString
            codec.get("mime").asString
            codec.get("maxBitrate").asInt
            listOf("adaptive", "tunneling", "hardwareAccelerated").forEach { codec.get(it).asBoolean }
            codec.getAsJsonArray("profiles").forEach {
                it.asJsonObject.get("profile").asInt
                it.asJsonObject.get("level").asInt
            }
            codec.getAsJsonArray("modes").forEach {
                it.asJsonObject.get("width").asInt
                it.asJsonObject.get("height").asInt
                it.asJsonObject.get("maxFrameRate").asDouble
            }
        }
        Gson().fromJson(root, HardwareCapabilityReport::class.java)
    }.getOrNull()
}
