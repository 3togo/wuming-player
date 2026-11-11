package com.wuming.tv.core.hongguo

import com.wuming.tv.core.catalog.*
import java.net.URI
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Current first-party website grammar, shared by Android, Leanback and desktop. */
class HongGuoCatalogSourceExecutor(private val spec: HongGuoSiteSpec, private val transport: HongGuoTransport) : CatalogSourceExecutor {
    override val source = CatalogSource(spec.key, spec.name)
    private val headers = mapOf("User-Agent" to HongGuoSiteSpec.USER_AGENT, "Referer" to HongGuoSiteSpec.BASE_URL)

    override fun home() = browseHome().items
    override fun browseHome(): CatalogHome {
        val document = page(HongGuoSiteSpec.BASE_URL)
        val types = document.select(".nav a[href]").mapNotNull { a ->
            siteLink(a.attr("href"), TYPE)?.let { CatalogType(URI(it).rawPath, a.text()) }
        }.distinctBy { it.id }
        return CatalogHome(items(document), types).withConfiguredCategories(spec.categories)
    }

    override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage {
        require(page in 1..10000) { "Invalid HongGuo category page" }
        val category = sitePage(typeId, TYPE)
        val location = if (page == 1) category else category.removeSuffix(".html") + "/page/$page.html"
        val document = page(location)
        val pagePath = Regex(Regex.escape(URI(category).rawPath.removeSuffix(".html")) + "/page/(\\d+)\\.html")
        val pages = document.select("a[href]").mapNotNull {
            siteLink(it.attr("href"), pagePath)?.let { url ->
                pagePath.matchEntire(URI(url).rawPath)?.groupValues?.get(1)?.toIntOrNull()
            }
        }
        return CatalogPage(items(document), page, maxOf(page, pages.maxOrNull() ?: page))
    }

    override fun search(query: String): List<CatalogItem> {
        require(query.isNotBlank() && query.length <= 512) { "Invalid HongGuo search query" }
        val encoded = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return items(page(HongGuoSiteSpec.BASE_URL + "vod/search/wd/$encoded.html"))
    }

    override fun detail(id: String): CatalogDetail {
        val location = sitePage(id, DETAIL)
        val document = page(location)
        val title = document.selectFirst("h1")?.text().orEmpty()
        require(title.isNotBlank()) { "HongGuo detail contains no title" }
        val lines = document.select(".playurl").mapIndexedNotNull { index, list ->
            val episodes = list.select("a[href]").mapNotNull { a ->
                siteLink(a.attr("href"), PLAY)?.let { CatalogEpisode(a.text().ifBlank { "Play" }, it) }
            }.distinctBy { it.id }
            if (episodes.isEmpty()) null else {
                val label = document.select(".nav-tabs [data-target]").firstOrNull { tab ->
                    val target = tab.attr("data-target").removePrefix(".")
                    target.isNotBlank() && target in list.classNames()
                }?.text().orEmpty().ifBlank { "Line ${index + 1}" }
                CatalogLine("line-${index + 1}", label, episodes)
            }
        }
        return CatalogDetail(CatalogItem(source.key, location, title,
            picture(document.selectFirst(".detail .pic img"))),
            content = document.selectFirst(".vod-content")?.text().orEmpty(), lines = lines)
    }

    override fun play(flag: String, id: String): CatalogPlayback {
        val location = sitePage(id, PLAY)
        val media = page(location).selectFirst(".video[data-play]")?.attr("data-play").orEmpty()
        val uri = runCatching { URI(media) }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && media.none(Char::isISOControl) &&
            uri.path.orEmpty().lowercase().let { path -> MEDIA_SUFFIXES.any(path::endsWith) }) {
            "HongGuo did not provide a direct media URL"
        }
        return CatalogPlayback(supported = true, url = media, headers = headers)
    }

    override fun cancel() = transport.cancel()
    private fun page(url: String) = Jsoup.parse(transport.get(url, headers), url)
    private fun sitePage(value: String, pattern: Regex): String =
        requireNotNull(siteLink(value, pattern)) { "Invalid HongGuo page URL" }
    private fun siteLink(value: String, pattern: Regex): String? = runCatching {
        val uri = URI(HongGuoSiteSpec.BASE_URL).resolve(value).normalize()
        uri.toString().takeIf { HongGuoSiteSpec.isSiteUrl(it) && uri.rawQuery == null && uri.rawFragment == null && pattern.matches(uri.rawPath) }
    }.getOrNull()
    private fun picture(image: Element?): String {
        val raw = image?.attr("data-original")?.ifBlank { image.attr("data-src") }?.ifBlank { image.attr("src") }.orEmpty()
        if (raw.isBlank()) return ""
        return runCatching { URI(HongGuoSiteSpec.BASE_URL).resolve(raw).toString() }
            .getOrDefault("").takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
    }
    private fun items(document: Document): List<CatalogItem> = document.select("a[href]").mapNotNull { a ->
        val id = siteLink(a.attr("href"), DETAIL) ?: return@mapNotNull null
        val image = a.selectFirst("img")
        val name = a.attr("title").ifBlank { image?.attr("alt").orEmpty() }.ifBlank { a.text() }
        if (name.isBlank()) null else CatalogItem(source.key, id, name, picture(image))
    }.groupBy { it.id }.values.map { cards -> cards.firstOrNull { it.picture.isNotBlank() } ?: cards.first() }

    private companion object {
        val TYPE = Regex("/vod/type/id/[a-zA-Z0-9_-]+\\.html")
        val DETAIL = Regex("/vod/detail/id/[a-zA-Z0-9_-]+\\.html")
        val PLAY = Regex("/vod/play/id/[a-zA-Z0-9_-]+/sid/\\d+/nid/\\d+\\.html")
        val MEDIA_SUFFIXES = listOf(".m3u8", ".mp4", ".mkv", ".webm")
    }
}
