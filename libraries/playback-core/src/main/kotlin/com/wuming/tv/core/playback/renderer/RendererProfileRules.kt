package com.wuming.tv.core.playback.renderer

import com.google.gson.Gson
import com.google.gson.JsonParser

/** Only reviewed, exact-model/firmware evidence belongs in the shipped catalog. */
object RendererProfileRules {
    private val shipped by lazy {
        val stream = RendererProfileRules::class.java.getResourceAsStream("/renderer-capability-rules.json")
            ?: return@lazy emptyList<BundledRendererRule>()
        stream.bufferedReader().use { reader ->
            val root = JsonParser.parseReader(reader).asJsonObject
            require(root.get("schemaVersion").asInt == 1)
            root.getAsJsonArray("rules").map { element ->
                Gson().fromJson(element, BundledRendererRule::class.java).also {
                    require(it.manufacturer.isNotBlank() && it.model.isNotBlank())
                    require(it.firmware.isNotBlank() && it.firmware != "unknown")
                    require(it.evidenceReference.isNotBlank())
                    require(it.capability in RendererCapability.entries && it.state in CapabilityState.entries)
                }
            }
        }
    }
    fun bundled(): List<BundledRendererRule> = shipped.toList()
}
