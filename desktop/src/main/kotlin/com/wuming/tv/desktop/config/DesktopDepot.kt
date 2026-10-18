package com.wuming.tv.desktop.config

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.wuming.tv.core.source.config.TvBoxWarehouse
import java.io.InputStream
import java.nio.charset.StandardCharsets

data class DesktopDepotSource(
    val name: String,
    val location: String,
    val warehouse: Boolean = false,
) {
    override fun toString(): String = name
}

data class DesktopDepot(
    val vod: List<DesktopDepotSource>,
    val live: List<DesktopTvBoxLive>,
) {
    companion object {
        fun load(
            openResource: () -> InputStream? = {
                DesktopDepot::class.java.getResourceAsStream("/depot.json")
            },
        ): DesktopDepot {
            val stream = requireNotNull(openResource()) { "Bundled depot.json is missing." }
            val root = stream.use {
                JsonParser.parseString(it.readBytes().toString(StandardCharsets.UTF_8)).asJsonObject
            }
            return DesktopDepot(
                vod = array(root.get("vod")).mapNotNull { source(it) }
                    .distinctBy(DesktopDepotSource::location),
                live = array(root.get("live")).mapNotNull { source(it) }
                    .map { DesktopTvBoxLive(it.name, it.location) }
                    .distinctBy(DesktopTvBoxLive::location),
            )
        }

        private fun array(element: JsonElement?): List<JsonElement> =
            element?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty()

        private fun source(element: JsonElement): DesktopDepotSource? {
            if (!element.isJsonObject) return null
            val value = element.asJsonObject
            val location = value.get("url")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim().orEmpty()
            if (location.isEmpty() || location.any(Character::isISOControl)) return null
            val name = value.get("name")?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim().orEmpty()
            val warehouse = TvBoxWarehouse.isLikelyIndex(name,
                value.get("warehouse")?.takeIf(JsonElement::isJsonPrimitive)?.asBoolean == true)
            return DesktopDepotSource(name.ifBlank { location }, location, warehouse)
        }
    }
}
