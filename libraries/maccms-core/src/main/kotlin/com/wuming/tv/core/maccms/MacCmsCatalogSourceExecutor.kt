package com.wuming.tv.core.maccms

import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogHome
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogPage
import com.wuming.tv.core.catalog.CatalogPlayback
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.catalog.withConfiguredCategories
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.URI

/**
 * Executes a TVBox `type` 0/1 collection site.
 *
 * The request contract mirrors Android's `SiteApi`: one HTTP GET per operation with `ac`,
 * `t`, `pg`, `ids` or `wd` query parameters, plus the optional site `extend` value. No
 * provider code runs, so this source is safe to enable without user approval.
 */
class MacCmsCatalogSourceExecutor(
    private val spec: MacCmsSiteSpec,
    private val transport: MacCmsTransport,
) : CatalogSourceExecutor {

    private val gson = Gson()

    override val source: CatalogSource = CatalogSource(spec.key, spec.name)

    override fun home(): List<CatalogItem> = browseHome().items

    override fun browseHome(): CatalogHome = MacCmsPayloadParser
        .home(fetch(emptyMap()), spec.xml, source)
        .withConfiguredCategories(spec.categories)

    override fun category(
        typeId: String,
        page: Int,
        filters: Map<String, String>,
    ): CatalogPage {
        require(typeId.isNotBlank()) { "Category ID is required" }
        val requestedPage = page.coerceAtLeast(1)
        val params = requestParams("t" to typeId, "pg" to requestedPage.toString())
        if (!spec.xml && filters.isNotEmpty()) params["f"] = gson.toJson(filters)
        return collection(
            params,
            { MacCmsPayloadParser.category(it, spec.xml, source, requestedPage) },
            { it.items.isEmpty() },
        )
    }

    override fun search(query: String): List<CatalogItem> {
        require(query.isNotBlank()) { "Search query is required" }
        return collection(
            requestParams("wd" to query, "quick" to "0", "pg" to "1"),
            { MacCmsPayloadParser.search(it, spec.xml, source) },
            { it.isEmpty() },
        )
    }

    override fun detail(id: String): CatalogDetail {
        require(id.isNotBlank()) { "Detail ID is required" }
        val payload = collection(requestParams("ids" to id), { it }) {
            // Inspect the raw list: valid detail items may omit vod_id, so the
            // search decoder cannot decide whether a detail response is empty.
            val list = runCatching { JsonParser.parseString(it).asJsonObject.get("list") }.getOrNull()
            list != null && list.isJsonArray && list.asJsonArray.size() == 0
        }
        return MacCmsPayloadParser.detail(payload, spec.xml, source, id)
    }

    /** One compatibility retry for an empty JSON collection, never for transport/parse failures. */
    private fun <T> collection(
        params: Map<String, String>,
        decode: (String) -> T,
        isEmpty: (T) -> Boolean,
    ): T {
        val primary = decode(fetch(params))
        if (spec.xml || !isEmpty(primary)) return primary
        return decode(fetch(params + ("ac" to "videolist")))
    }

    override fun play(flag: String, id: String): CatalogPlayback {
        val location = id.trim()
        if (isHttp(location)) return CatalogPlayback(supported = true, url = location)
        return CatalogPlayback(message = "MacCMS source returned an unusable playback URL.")
    }

    /** MacCMS XML uses `ac=videolist` for every action; JSON splits list/detail. */
    private fun requestParams(vararg entries: Pair<String, String>): LinkedHashMap<String, String> {
        val params = linkedMapOf("ac" to if (spec.xml) "videolist" else "detail")
        if (spec.ext.isNotBlank()) params["extend"] = spec.ext
        entries.forEach { (name, value) -> params[name] = value }
        return params
    }

    private fun fetch(params: Map<String, String>): String = transport.get(spec.api, spec.headers, params)

    private fun isHttp(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
