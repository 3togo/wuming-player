package com.wuming.tv.core.catalog

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/** Portable formatting contract for provider synopsis fragments, without remote resources or scripts. */
object CatalogDescriptionHtml {
    private val formatting = Safelist.none().addTags(
        "p", "br", "b", "strong", "i", "em", "u", "s", "ul", "ol", "li", "blockquote", "pre", "code", "hr",
    )

    fun fragment(value: String): String {
        val source = Jsoup.parseBodyFragment(value)
        val plain = source.body().children().isEmpty()
        val clean = Cleaner(formatting).clean(source).apply {
            outputSettings(Document.OutputSettings().prettyPrint(false))
        }.body().html()
        return if (plain) clean.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>") else clean
    }

    fun text(value: String): String = Entities.escape(value)
}
