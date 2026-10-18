package com.wuming.tv.core.catalog

import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.util.Base64

/** One TVBox `type` 4 endpoint. Provider code remains on the remote HTTP service. */
data class RemoteSpiderSiteSpec(
    val key: String,
    val name: String,
    val api: String,
    val ext: String = "",
    val headers: Map<String, String> = emptyMap(),
    val categories: List<String> = emptyList(),
)

fun interface RemoteSpiderTransport {
    fun get(api: String, headers: Map<String, String>, params: Map<String, String>): String
}

/**
 * Executes the bounded HTTP query dialect used by Android's TVBox `type` 4 sources.
 * The platform transport owns networking, redirects and response-size policy.
 */
class RemoteSpiderCatalogSourceExecutor(
    private val spec: RemoteSpiderSiteSpec,
    private val transport: RemoteSpiderTransport,
    private val gson: Gson = Gson(),
) : CatalogSourceExecutor {

    override val source = CatalogSource(spec.key, spec.name)
    private val codec = CatVodCatalogCodec(source)
    private var resolvedExt = spec.ext

    override fun home(): List<CatalogItem> = browseHome().items

    override fun browseHome(): CatalogHome {
        if (resolvedExt.startsWith("http://") || resolvedExt.startsWith("https://")) {
            val fetched = transport.get(resolvedExt, spec.headers, emptyMap())
            if (fetched.isNotEmpty()) resolvedExt = fetched
        }
        return codec.home(fetch(mapOf("filter" to "true")))
            .withConfiguredCategories(spec.categories)
    }

    override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage {
        require(typeId.isNotBlank()) { "Category ID is required" }
        val requestedPage = page.coerceAtLeast(1)
        val params = linkedMapOf(
            "ac" to "detail",
            "t" to typeId,
            "pg" to requestedPage.toString(),
            "ext" to Base64.getUrlEncoder().encodeToString(
                gson.toJson(filters).toByteArray(StandardCharsets.UTF_8),
            ),
        )
        return codec.category(fetch(params), requestedPage)
    }

    override fun search(query: String): List<CatalogItem> {
        require(query.isNotBlank()) { "Search query is required" }
        return codec.search(fetch(mapOf("wd" to query, "quick" to "false", "extend" to "")))
    }

    override fun detail(id: String): CatalogDetail {
        require(id.isNotBlank()) { "Detail ID is required" }
        return codec.detail(fetch(mapOf("ac" to "detail", "ids" to id)), id)
    }

    override fun play(flag: String, id: String): CatalogPlayback {
        require(id.isNotBlank()) { "Playback ID is required" }
        return codec.playback(fetch(mapOf("play" to id, "flag" to flag)))
    }

    private fun fetch(params: Map<String, String>): String {
        val request = LinkedHashMap(params)
        if (resolvedExt.isNotBlank()) request["extend"] = resolvedExt
        return transport.get(spec.api, spec.headers, request)
    }
}
