package com.wuming.tv.core.maccms

import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogEpisode
import com.wuming.tv.core.catalog.CatalogHome
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogLine
import com.wuming.tv.core.catalog.CatalogPage
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatalogType
import com.wuming.tv.core.catalog.CatVodCatalogCodec
import com.wuming.tv.core.base.native.NativeLoader
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Decodes the two MacCMS collection payload shapes.
 *
 * The JSON shape is the ancestor of the CatVod protocol, so it reuses [CatVodCatalogCodec]
 * verbatim: both expose `class`, `list`, `pagecount` and the same `vod_*` field names.
 * The XML shape (`<rss><list><video>`) is parsed here because no SimpleXML port exists.
 */
internal object MacCmsPayloadParser {

    private val nativeAvailable: Boolean get() = NativeLoader.available

    private val gson = Gson()

    private val documentBuilderFactory: DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { isXIncludeAware = false }
        isExpandEntityReferences = false
    }

    fun home(payload: String, xml: Boolean, source: CatalogSource): CatalogHome {
        if (xml && nativeAvailable) {
            val json = nativeParseXml(payload)
            if (json.isNotEmpty()) {
                val result = gson.fromJson(json, MacCmsXmlResultJson::class.java)
                if (result != null) {
                    return CatalogHome(
                        items = result.items.map { it.toCatalogItem(source) },
                        types = result.types.map { it.toCatalogType() },
                    )
                }
            }
        }
        return if (xml) CatalogHome(xmlItems(document(payload), source), xmlTypes(document(payload)))
        else CatVodCatalogCodec(source).home(payload)
    }

    fun category(payload: String, xml: Boolean, source: CatalogSource, requestedPage: Int): CatalogPage {
        if (xml && nativeAvailable) {
            val json = nativeParseXml(payload)
            if (json.isNotEmpty()) {
                val result = gson.fromJson(json, MacCmsXmlResultJson::class.java)
                if (result != null) {
                    return CatalogPage(
                        items = result.items.map { it.toCatalogItem(source) },
                        page = result.page.coerceAtLeast(1),
                        pageCount = result.page_count.coerceAtLeast(0),
                        total = result.total.coerceAtLeast(0),
                    )
                }
            }
        }
        return if (xml) xmlCategory(payload, source, requestedPage)
        else CatVodCatalogCodec(source).category(payload, requestedPage)
    }

    fun search(payload: String, xml: Boolean, source: CatalogSource): List<CatalogItem> {
        if (xml && nativeAvailable) {
            val json = nativeParseXml(payload)
            if (json.isNotEmpty()) {
                val result = gson.fromJson(json, MacCmsXmlResultJson::class.java)
                if (result != null) {
                    return result.items.map { it.toCatalogItem(source) }
                }
            }
        }
        return if (xml) xmlItems(document(payload), source) else CatVodCatalogCodec(source).search(payload)
    }

    fun detail(payload: String, xml: Boolean, source: CatalogSource, requestedId: String): CatalogDetail {
        if (xml && nativeAvailable) {
            val json = nativeParseXml(payload)
            if (json.isNotEmpty()) {
                val result = gson.fromJson(json, MacCmsXmlResultJson::class.java)
                if (result != null && result.detail != null) {
                    val item = result.detail.toCatalogItem(source)
                    return CatalogDetail(
                        item = item.copy(id = requestedId.trim().ifBlank { item.id }),
                        content = result.detail.des,
                        actor = result.detail.actor,
                        director = result.detail.director,
                        area = result.detail.area,
                        lines = result.detail.lines.map { it.toCatalogLine() },
                    )
                }
            }
        }
        return if (xml) xmlDetail(payload, source, requestedId)
        else CatVodCatalogCodec(source).detail(payload, requestedId)
    }

    // ------------------------------------------------------------------
    // XML
    // ------------------------------------------------------------------

    private fun xmlCategory(payload: String, source: CatalogSource, requestedPage: Int): CatalogPage {
        val root = document(payload)
        val list = root.elements("list").firstOrNull()
        return CatalogPage(
            items = xmlItems(root, source),
            page = (list?.integer("page") ?: requestedPage).coerceAtLeast(1),
            pageCount = (list?.integer("pagecount") ?: 0).coerceAtLeast(0),
            total = (list?.integer("recordcount") ?: 0).coerceAtLeast(0),
        )
    }

    private fun xmlDetail(payload: String, source: CatalogSource, requestedId: String): CatalogDetail {
        val video = document(payload).elements("video").firstOrNull()
            ?: throw IllegalArgumentException("MacCMS source returned no detail item")
        val item = xmlItem(video, source)
            ?: throw IllegalArgumentException("MacCMS source returned an invalid detail item")
        return CatalogDetail(
            item = item.copy(id = requestedId.trim().ifBlank { item.id }),
            content = video.text("des"),
            actor = video.text("actor"),
            director = video.text("director"),
            area = video.text("area"),
            lines = video.elements("dd").mapIndexedNotNull(::xmlLine),
        )
    }

    /**
     * Mirrors Android `Flag.setEpisodes`: `name$url` pairs per `#`-separated entry, and a bare
     * URL becomes episode `01`, `02`, … because many XML sources omit episode names.
     */
    private fun xmlLine(index: Int, node: Element): CatalogLine? {
        val episodes = node.textContent.split('#')
            .mapIndexedNotNull { position, token -> episode(position + 1, token) }
        if (episodes.isEmpty()) return null
        val flag = node.attribute("flag").ifBlank { "line-${index + 1}" }
        return CatalogLine(flag = flag, name = flag, episodes = episodes)
    }

    private fun episode(ordinal: Int, token: String): CatalogEpisode? {
        val value = token.trim()
        if (value.isEmpty()) return null
        val number = "%02d".format(ordinal)
        val separator = value.indexOf('$')
        if (separator < 0 || separator == value.lastIndex) return CatalogEpisode(number, value)
        return CatalogEpisode(
            value.substring(0, separator).trim().ifBlank { number },
            value.substring(separator + 1).trim(),
        )
    }

    private fun xmlTypes(root: Element): List<CatalogType> = root.elements("class")
        .flatMap { it.childElementList() }
        .filter { it.tagName == "ty" }
        .mapNotNull { type ->
            val id = type.attribute("id")
            if (id.isEmpty()) null else CatalogType(id, type.textContent.trim().ifBlank { id })
        }.distinctBy(CatalogType::id)

    private fun xmlItems(root: Element, source: CatalogSource): List<CatalogItem> =
        root.elements("video").mapNotNull { xmlItem(it, source) }

    private fun xmlItem(video: Element, source: CatalogSource): CatalogItem? {
        val id = video.text("id")
        if (id.isEmpty()) return null
        return CatalogItem(
            sourceKey = source.key,
            id = id,
            name = video.text("name").ifBlank { id },
            picture = video.text("pic"),
            remarks = video.text("note"),
            year = video.text("year"),
        )
    }

    private fun document(payload: String): Element = runCatching {
        documentBuilderFactory.newDocumentBuilder()
            .parse(ByteArrayInputStream(payload.toByteArray(StandardCharsets.UTF_8)))
            .documentElement
    }.getOrElse { throw IllegalArgumentException("MacCMS source returned invalid XML") }

    private fun Element.elements(name: String): List<Element> {
        val nodes = getElementsByTagName(name)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.childElementList(): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun Element.text(name: String): String =
        getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()

    private fun Element.attribute(name: String): String = getAttribute(name).orEmpty().trim()

    private fun Element.integer(name: String): Int? = attribute(name).toIntOrNull()

    @JvmStatic
    private external fun nativeParseXml(payload: String): String

    private data class MacCmsXmlResultJson(
        val items: List<MacCmsItemJson> = emptyList(),
        val types: List<MacCmsTypeJson> = emptyList(),
        val page: Int = 0,
        val page_count: Int = 0,
        val total: Int = 0,
        val detail: MacCmsDetailJson? = null,
    )

    private data class MacCmsItemJson(
        val id: String = "",
        val name: String = "",
        val pic: String = "",
        val note: String = "",
        val year: String = "",
    ) {
        fun toCatalogItem(source: CatalogSource): CatalogItem = CatalogItem(
            sourceKey = source.key,
            id = id,
            name = name.ifBlank { id },
            picture = pic,
            remarks = note,
            year = year,
        )
    }

    private data class MacCmsTypeJson(
        val id: String = "",
        val name: String = "",
    ) {
        fun toCatalogType(): CatalogType = CatalogType(id, name.ifBlank { id })
    }

    private data class MacCmsDetailJson(
        val id: String = "",
        val name: String = "",
        val pic: String = "",
        val note: String = "",
        val year: String = "",
        val des: String = "",
        val actor: String = "",
        val director: String = "",
        val area: String = "",
        val lines: List<MacCmsLineJson> = emptyList(),
    ) {
        fun toCatalogItem(source: CatalogSource): CatalogItem = CatalogItem(
            sourceKey = source.key,
            id = id,
            name = name.ifBlank { id },
            picture = pic,
            remarks = note,
            year = year,
        )
    }

    private data class MacCmsLineJson(
        val flag: String = "",
        val name: String = "",
        val episodes: List<MacCmsEpisodeJson> = emptyList(),
    ) {
        fun toCatalogLine(): CatalogLine = CatalogLine(flag, name, episodes.map { it.toCatalogEpisode() })
    }

    private data class MacCmsEpisodeJson(
        val name: String = "",
        val url: String = "",
    ) {
        fun toCatalogEpisode(): CatalogEpisode = CatalogEpisode(name, url)
    }
}
