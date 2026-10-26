package com.wuming.tv.core.catalog

import com.wuming.tv.core.playback.MediaUrlClassifier

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.tv.core.base.native.NativeLoader
import java.net.URI

/** Maps the stable CatVod JSON protocol without depending on any provider runtime. */
class CatVodCatalogCodec(private val source: CatalogSource) {

    private val nativeAvailable: Boolean = NativeLoader.available
    private val gson = com.google.gson.Gson()

    fun search(json: String): List<CatalogItem> {
        if (nativeAvailable) {
            val resultJson = nativeSearch(json, source.key)
            if (resultJson.isNotEmpty()) {
                val items = gson.fromJson(resultJson, Array<CatalogItemNativeJson>::class.java)
                if (items != null) return items.mapNotNull { it.toCatalogItem() }
            }
        }
        return array(root(json), "list").mapNotNull(::item)
    }

    fun home(json: String): CatalogHome {
        if (nativeAvailable) {
            val resultJson = nativeHome(json, source.key)
            if (resultJson.isNotEmpty()) {
                val home = gson.fromJson(resultJson, CatalogHomeNativeJson::class.java)
                if (home != null) return home.toCatalogHome(source)
            }
        }
        val value = root(json)
        val rootFilters = value.get("filters")?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val types = array(value, "class").mapNotNull { element ->
            val type = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val id = string(type, "type_id").ifBlank { string(type, "id") }.trim()
            if (id.isEmpty()) return@mapNotNull null
            val embedded = filterElements(type.get("filters"))
            val scoped = filterElements(rootFilters?.get(id))
            CatalogType(
                id = id,
                name = string(type, "type_name").ifBlank { string(type, "name") }.ifBlank { id },
                folder = string(type, "type_flag") == "1",
                filters = (embedded + scoped).mapNotNull(::filter).distinctBy(CatalogFilter::key),
            )
        }
        return CatalogHome(array(value, "list").mapNotNull(::item), types)
    }

    fun category(json: String, requestedPage: Int = 1): CatalogPage {
        if (nativeAvailable) {
            val resultJson = nativeCategory(json, source.key, requestedPage)
            if (resultJson.isNotEmpty()) {
                val page = gson.fromJson(resultJson, CatalogPageNativeJson::class.java)
                if (page != null) return page.toCatalogPage(source)
            }
        }
        val value = root(json)
        return CatalogPage(
            items = array(value, "list").mapNotNull(::item),
            page = integer(value, "page", requestedPage).coerceAtLeast(1),
            pageCount = integer(value, "pagecount", 0).coerceAtLeast(0),
            total = integer(value, "total", 0).coerceAtLeast(0),
        )
    }

    fun detail(json: String, requestedId: String = ""): CatalogDetail {
        if (nativeAvailable) {
            val resultJson = nativeDetail(json, source.key, requestedId)
            if (resultJson.isNotEmpty()) {
                val detail = gson.fromJson(resultJson, CatalogDetailNativeJson::class.java)
                if (detail != null) return detail.toCatalogDetail(source)
            }
        }
        val value = array(root(json), "list").firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: throw IllegalArgumentException("CatVod provider returned no detail item")
        val item = item(value, requestedId) ?: throw IllegalArgumentException("CatVod provider returned an invalid detail item")
        return CatalogDetail(
            item = item,
            content = string(value, "vod_content"),
            actor = string(value, "vod_actor"),
            director = string(value, "vod_director"),
            area = string(value, "vod_area"),
            lines = lines(string(value, "vod_play_from"), string(value, "vod_play_url")),
        )
    }

    fun playback(json: String): CatalogPlayback {
        // Playback admission uses the same policy as Android's sniffer, including audio.
        // The legacy native codec has a separate extension list and must not bypass it.
        val value = root(json)
        val location = playbackUrl(value)
        val validHttp = isHttp(location)
        val interactive = integer(value, "parse", integer(value, "jx", 0)) != 0 && !MediaUrlClassifier.isMediaUrl(location)
        return CatalogPlayback(
            supported = validHttp && !interactive,
            url = if (validHttp && !interactive) location else "",
            format = string(value, "format"),
            message = when {
                !validHttp -> "CatVod provider did not return an HTTP(S) playback URL."
                interactive -> "CatVod provider returned a web page that requires browser sniffing."
                else -> ""
            },
            headers = headers(value.get("header")),
        )
    }

    private fun item(element: JsonElement): CatalogItem? =
        element.takeIf(JsonElement::isJsonObject)?.asJsonObject?.let(::item)

    private fun item(value: JsonObject, requestedId: String = ""): CatalogItem? {
        val id = string(value, "vod_id").trim().ifBlank { requestedId.trim() }
        if (id.isEmpty()) return null
        return CatalogItem(
            sourceKey = source.key,
            id = id,
            name = string(value, "vod_name").ifBlank { id },
            picture = string(value, "vod_pic"),
            remarks = string(value, "vod_remarks"),
            year = string(value, "vod_year"),
            kind = when {
                string(value, "action").isNotBlank() -> CatalogItemKind.ACTION
                string(value, "vod_tag").equals("folder", true) ||
                    value.get("cate")?.isJsonNull == false -> CatalogItemKind.FOLDER
                else -> CatalogItemKind.MEDIA
            },
            action = string(value, "action"),
            typeName = string(value, "type_name"),
            area = string(value, "vod_area"),
            director = string(value, "vod_director"),
            actor = string(value, "vod_actor"),
            content = string(value, "vod_content"),
        )
    }

    private fun filter(element: JsonElement): CatalogFilter? {
        val value = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
        val key = string(value, "key").trim()
        if (key.isEmpty()) return null
        val values = array(value, "value").mapNotNull { option ->
            val item = option.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
            val id = primitiveString(item, "v") ?: return@mapNotNull null
            CatalogFilterValue(string(item, "n").ifBlank { id.ifBlank { "All" } }, id)
        }.distinctBy(CatalogFilterValue::value)
        if (values.isEmpty()) return null
        return CatalogFilter(
            key = key,
            name = string(value, "name").ifBlank { key },
            initialValue = primitiveString(value, "init"),
            values = values,
        )
    }

    /** Android's FilterAdapter accepts either one filter object or an array for a type. */
    private fun filterElements(element: JsonElement?): List<JsonElement> = when {
        element?.isJsonArray == true -> element.asJsonArray.toList()
        element?.isJsonObject == true -> listOf(element)
        else -> emptyList()
    }

    private fun lines(from: String, urls: String): List<CatalogLine> {
        val flags = from.split("$$$")
        return urls.split("$$$").mapIndexedNotNull { index, encoded ->
            val episodes = encoded.split('#').mapNotNull { token ->
                val separator = token.indexOf('$')
                if (separator < 0 || separator == token.lastIndex) null else CatalogEpisode(
                    token.substring(0, separator).ifBlank { "Play" },
                    token.substring(separator + 1),
                )
            }
            if (episodes.isEmpty()) null else CatalogLine(
                flag = flags.getOrNull(index).orEmpty().ifBlank { "line-${index + 1}" },
                name = CatalogLineNames.display(flags.getOrNull(index).orEmpty().ifBlank { "Line ${index + 1}" }),
                episodes = episodes,
            )
        }
    }

    private fun playbackUrl(value: JsonObject): String {
        val direct = value.get("url")
        if (direct?.isJsonPrimitive == true) return direct.asString.trim()
        if (direct?.isJsonArray == true) {
            return direct.asJsonArray.firstOrNull { it.isJsonPrimitive && isHttp(it.asString) }
                ?.asString?.trim().orEmpty()
        }
        if (direct?.isJsonObject == true) {
            // Mirrors Android's UrlAdapter/Url model. Providers may return a selected
            // object-form URL with {values:[{n, v}], position:n} rather than a string.
            val objectValue = direct.asJsonObject
            val values = objectValue.get("values")?.takeIf(JsonElement::isJsonArray)?.asJsonArray
                ?.mapNotNull { entry ->
                    entry.takeIf(JsonElement::isJsonObject)?.asJsonObject?.get("v")
                        ?.takeIf(JsonElement::isJsonPrimitive)?.asString?.trim()
                }.orEmpty()
            val selected = integer(objectValue, "position", 0).coerceIn(0, (values.size - 1).coerceAtLeast(0))
            return values.getOrNull(selected)?.takeIf(::isHttp)
                ?: values.firstOrNull(::isHttp).orEmpty()
        }
        return ""
    }

    private fun headers(value: JsonElement?): Map<String, String> {
        if (value == null || value.isJsonNull) return emptyMap()
        val objectValue = when {
            value.isJsonObject -> value.asJsonObject
            value.isJsonPrimitive -> runCatching { JsonParser.parseString(value.asString).asJsonObject }.getOrNull()
            else -> null
        } ?: return emptyMap()
        return objectValue.entrySet().mapNotNull { (key, item) ->
            item.takeIf(JsonElement::isJsonPrimitive)?.asString?.let { key to it }
        }.toMap()
    }

    private fun root(json: String): JsonObject {
        CatalogAccessFailureClassifier.classify(json)?.let { throw CatalogAccessException(it) }
        return runCatching { JsonParser.parseString(json).asJsonObject }
            .getOrElse { throw IllegalArgumentException("CatVod provider returned invalid JSON") }
    }

    private fun array(root: JsonObject, name: String): List<JsonElement> =
        root.get(name)?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.toList().orEmpty()

    private fun string(root: JsonObject, name: String): String = runCatching {
        root.get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
    }.getOrDefault("")

    private fun primitiveString(root: JsonObject, name: String): String? = runCatching {
        root.get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString
    }.getOrNull()

    private fun integer(root: JsonObject, name: String, fallback: Int): Int = runCatching {
        root.get(name)?.takeIf(JsonElement::isJsonPrimitive)?.asString?.toInt() ?: fallback
    }.getOrDefault(fallback)

    private fun isHttp(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private external fun nativeSearch(json: String, sourceKey: String): String

    private external fun nativeHome(json: String, sourceKey: String): String

    private external fun nativeCategory(json: String, sourceKey: String, requestedPage: Int): String

    private external fun nativeDetail(json: String, sourceKey: String, requestedId: String): String

    private external fun nativePlayback(json: String): String

    private data class CatalogItemNativeJson(
        val source_key: String = "",
        val id: String = "",
        val name: String = "",
        val picture: String = "",
        val remarks: String = "",
        val year: String = "",
        val kind: String = "MEDIA",
        val action: String = "",
        val type_name: String = "",
        val area: String = "",
        val director: String = "",
        val actor: String = "",
        val content: String = "",
    ) {
        fun toCatalogItem(): CatalogItem? {
            if (id.isEmpty()) return null
            return CatalogItem(
                sourceKey = source_key,
                id = id,
                name = name.ifBlank { id },
                picture = picture,
                remarks = remarks,
                year = year,
                kind = when (kind) {
                    "ACTION" -> CatalogItemKind.ACTION
                    "FOLDER" -> CatalogItemKind.FOLDER
                    else -> CatalogItemKind.MEDIA
                },
                action = action,
                typeName = type_name,
                area = area,
                director = director,
                actor = actor,
                content = content,
            )
        }
    }

    private data class CatalogFilterValueNativeJson(val name: String = "", val value: String = "")

    private data class CatalogFilterNativeJson(
        val key: String = "",
        val name: String = "",
        val init: String? = null,
        val values: List<CatalogFilterValueNativeJson> = emptyList(),
    ) {
        fun toCatalogFilter(): CatalogFilter? {
            if (key.isEmpty()) return null
            val vals = values.map { CatalogFilterValue(it.name, it.value) }.distinctBy(CatalogFilterValue::value)
            if (vals.isEmpty()) return null
            return CatalogFilter(key, name.ifBlank { key }, init, vals)
        }
    }

    private data class CatalogTypeNativeJson(
        val id: String = "",
        val name: String = "",
        val folder: Boolean = false,
        val filters: List<CatalogFilterNativeJson> = emptyList(),
    ) {
        fun toCatalogType(): CatalogType = CatalogType(
            id,
            name.ifBlank { id },
            folder,
            filters.mapNotNull { it.toCatalogFilter() }.distinctBy(CatalogFilter::key),
        )
    }

    private data class CatalogHomeNativeJson(
        val items: List<CatalogItemNativeJson> = emptyList(),
        val types: List<CatalogTypeNativeJson> = emptyList(),
    ) {
        fun toCatalogHome(source: CatalogSource): CatalogHome = CatalogHome(
            items.mapNotNull { it.toCatalogItem() },
            types.map { it.toCatalogType() },
        )
    }

    private data class CatalogPageNativeJson(
        val items: List<CatalogItemNativeJson> = emptyList(),
        val page: Int = 1,
        val page_count: Int = 0,
        val total: Int = 0,
    ) {
        fun toCatalogPage(source: CatalogSource): CatalogPage = CatalogPage(
            items.mapNotNull { it.toCatalogItem() },
            page.coerceAtLeast(1),
            page_count.coerceAtLeast(0),
            total.coerceAtLeast(0),
        )
    }

    private data class CatalogEpisodeNativeJson(val name: String = "", val id: String = "")

    private data class CatalogLineNativeJson(
        val flag: String = "",
        val name: String = "",
        val episodes: List<CatalogEpisodeNativeJson> = emptyList(),
    ) {
        fun toCatalogLine(): CatalogLine = CatalogLine(flag, name, episodes.map { CatalogEpisode(it.name, it.id) })
    }

    private data class CatalogDetailNativeJson(
        val item: CatalogItemNativeJson = CatalogItemNativeJson(),
        val content: String = "",
        val actor: String = "",
        val director: String = "",
        val area: String = "",
        val lines: List<CatalogLineNativeJson> = emptyList(),
    ) {
        fun toCatalogDetail(source: CatalogSource): CatalogDetail {
            val catalogItem = item.toCatalogItem() ?: CatalogItem(sourceKey = source.key)
            return CatalogDetail(catalogItem, content, actor, director, area, lines.map { it.toCatalogLine() })
        }
    }

    private data class CatalogPlaybackNativeJson(
        val supported: Boolean = false,
        val url: String = "",
        val format: String = "",
        val message: String = "",
        val headers: Map<String, String> = emptyMap(),
    ) {
        fun toCatalogPlayback(): CatalogPlayback = CatalogPlayback(supported, url, format, message, headers)
    }
}
