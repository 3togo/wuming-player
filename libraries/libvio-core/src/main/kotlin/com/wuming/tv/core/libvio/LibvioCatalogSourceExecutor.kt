package com.wuming.tv.core.libvio

import com.google.gson.JsonParser
import com.wuming.tv.core.catalog.*
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** First-party direct-play adapter. No downloaded code, JavaScript execution, or native engine. */
class LibvioCatalogSourceExecutor(private val spec: LibvioSiteSpec, private val transport: LibvioTransport) : CatalogSourceExecutor {
    override val source = CatalogSource(spec.key, spec.name)
    private val session = LibvioBrowserSession(transport)
    override fun home() = browseHome().items
    override fun browseHome(): CatalogHome {
        val document = page(spec.baseUrl)
        val types = document.select(".stui-header__menu a[href], .stui_header__menu a[href]")
            .mapNotNull { a -> Regex("/type/(\\d+)\\.html").find(a.attr("href"))?.groupValues?.get(1)?.let { CatalogType(it, a.text()) } }
            .distinctBy { it.id }
        return CatalogHome(items(document), types).withConfiguredCategories(spec.categories)
    }
    override fun search(query: String): List<CatalogItem> {
        require(query.isNotBlank() && query.length <= 512) { "Search query is required and must be at most 512 characters" }
        val json = JsonParser.parseString(session.get(url("/index.php/ajax/suggest?mid=1&wd=" +
            URLEncoder.encode(query.trim(), "UTF-8") + "&limit=10"))).asJsonObject
        require(json.get("code")?.asInt == 1 && json.get("list")?.isJsonArray == true) { "Libvio search API returned an invalid response" }
        return json.getAsJsonArray("list").mapNotNull { value ->
            val item = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = item.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val name = item.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (!id.matches(Regex("\\d+")) || name.isBlank()) return@mapNotNull null
            val pic = item.get("pic")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            CatalogItem(source.key, url("/detail/$id.html"), Jsoup.parse(name).text(),
                if (pic.isBlank()) "" else URI(spec.baseUrl).resolve(pic).toString(), "", "")
        }.distinctBy { it.id }
    }
    override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage {
        require(typeId.matches(Regex("\\d+")) && page in 1..10000) { "Invalid Libvio category or page" }
        val document = page(url(if (page == 1) "/type/$typeId.html" else "/type/$typeId-$page.html"))
        val count = document.select(".stui-page a[href], .stui-page__item a[href]")
            .mapNotNull { Regex("/type/\\d+-(\\d+)\\.html").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return CatalogPage(items(document), page, count)
    }
    override fun detail(id: String): CatalogDetail {
        val location = sitePage(id, Regex("/detail/\\d+\\.html"))
        val document = page(location)
        val title = document.selectFirst(".vod-info h1, h1.title, .stui-content__detail .title")?.text().orEmpty()
        require(title.isNotBlank()) { "Libvio detail page contains no title" }
        val image = document.selectFirst("#js-poster-img, .vod-poster img, .stui-content__thumb img")
        val content = document.selectFirst(".detail-content")?.text().orEmpty()
        val lines = document.select(".playlist-panel:not(.netdisk-panel)").mapIndexedNotNull { index, panel ->
            val episodes = panel.select("a[href]").mapNotNull { a ->
                episode(a, location)
            }.distinctBy { it.id }
            if (episodes.isEmpty()) null else {
                val name = panel.selectFirst("h3, .panel-head")?.text().orEmpty().ifBlank { "Direct ${index + 1}" }
                CatalogLine("libvio-direct-$index", name, episodes)
            }
        }.ifEmpty {
            // Older Libvio pages use the original stui playlist containers.
            document.select(".stui-content__playlist").mapIndexedNotNull { index, panel ->
                val episodes = panel.select("a[href]").mapNotNull { episode(it, location) }.distinctBy { it.id }
                if (episodes.isEmpty()) null else CatalogLine("libvio-direct-$index", "Direct ${index + 1}", episodes)
            }
        }
        require(lines.isNotEmpty()) { "Libvio has no direct-play episodes for this title; cloud-drive lines are not supported by this adapter" }
        val metadata = document.select(".vod-meta .meta-item").map(Element::text)
        val actor = metadata.firstOrNull { it.startsWith("主演：") }?.substringAfter('：').orEmpty()
        val director = metadata.firstOrNull { it.startsWith("导演：") }?.substringAfter('：').orEmpty()
        return CatalogDetail(CatalogItem(source.key, location, title, picture(image, location), "", metadata.firstOrNull { it.matches(Regex("\\d{4}")) }.orEmpty()),
            content = content, actor = actor, director = director, lines = lines)
    }
    override fun play(flag: String, id: String): CatalogPlayback {
        val location = sitePage(id, Regex("/w/\\d+-\\d+-\\d+\\.html"))
        val document = page(location)
        val script = document.select("script").map(Element::data).firstOrNull { Regex("\\bvar\\s+player_\\w+\\s*=").containsMatchIn(it) }
            ?: error("Libvio player data is missing")
        val start = script.indexOf('{')
        require(start >= 0) { "Invalid Libvio player data" }
        val data = JsonParser.parseReader(com.google.gson.stream.JsonReader(java.io.StringReader(script.substring(start)))).asJsonObject
        var media = data.get("url")?.asString.orEmpty()
        when (data.get("encrypt")?.asInt ?: 0) {
            0, 3 -> Unit // Current site publishes a direct URL with encrypt=3.
            1 -> media = URLDecoder.decode(media.replace("+", "%2B"), "UTF-8")
            2 -> media = URLDecoder.decode(String(Base64.getDecoder().decode(media), Charsets.UTF_8).replace("+", "%2B"), "UTF-8")
            else -> error("Unsupported Libvio player URL encoding")
        }
        val uri = runCatching { URI(media) }.getOrNull()
        val direct = uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.path.orEmpty().lowercase().let { it.endsWith(".mp4") || it.endsWith(".m3u8") || it.endsWith(".mkv") || it.endsWith(".webm") }
        require(direct && media.none { it.isISOControl() }) { "Libvio did not provide a direct media URL; browser sniffing is not supported" }
        return CatalogPlayback(supported = true, url = media, headers = mapOf("Referer" to spec.baseUrl, "User-Agent" to LibvioBrowserSession.USER_AGENT))
    }
    override fun cancel() = transport.cancel()
    private fun page(location: String): Document = Jsoup.parse(session.get(location), location)
    private fun url(path: String) = URI(spec.baseUrl).resolve(path).toString()
    private fun sitePage(value: String, path: Regex): String {
        val resolved = URI(url(value)).normalize()
        val uri = URI(resolved.scheme, resolved.authority, resolved.path.replace(Regex("/+"), "/"), resolved.query, null)
        require(LibvioSiteSpec.isSiteUrl(uri.toString()) && uri.host.equals(URI(spec.baseUrl).host, true) && path.matches(uri.path)) { "Invalid Libvio page URL" }
        return uri.toString()
    }
    private fun episode(a: Element, base: String): CatalogEpisode? {
        val target = runCatching { URI(base).resolve(a.attr("href")).normalize() }.getOrNull() ?: return null
        if (!LibvioSiteSpec.isSiteUrl(target.toString()) || target.host != URI(spec.baseUrl).host || !Regex("/w/\\d+-\\d+-\\d+\\.html").matches(target.path)) return null
        return CatalogEpisode(name = a.text().ifBlank { "Play" }, id = target.toString())
    }
    private fun picture(image: Element?, base: String): String {
        val value = image?.attr("data-original")?.ifBlank { image.attr("data-src") }?.ifBlank { image.attr("src") }.orEmpty()
        if (value.isBlank()) return ""
        return runCatching { URI(base).resolve(value).toString() }.getOrDefault("")
    }
    private fun items(document: Document): List<CatalogItem> = document.select("a[href]").mapNotNull { a ->
        val target = runCatching { URI(document.baseUri()).resolve(a.attr("href")).normalize() }.getOrNull() ?: return@mapNotNull null
        if (!LibvioSiteSpec.isSiteUrl(target.toString()) || target.host != URI(spec.baseUrl).host || !Regex("/detail/\\d+\\.html").matches(target.path)) return@mapNotNull null
        val image = a.selectFirst("img")
        val name = a.attr("title").ifBlank { image?.attr("alt").orEmpty() }.ifBlank { a.text() }
        if (name.isBlank()) return@mapNotNull null
        CatalogItem(source.key, target.toString(), name, picture(image ?: a, document.baseUri()), a.selectFirst(".pic-text")?.text().orEmpty(), "")
    }.groupBy { it.id }.values.map { cards -> cards.firstOrNull { it.picture.isNotBlank() } ?: cards.first() }
}
