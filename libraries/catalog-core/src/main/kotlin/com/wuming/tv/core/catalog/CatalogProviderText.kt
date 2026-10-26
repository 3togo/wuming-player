package com.wuming.tv.core.catalog

/** Plain-text presentation of CatVod's annotated cast/director links. */
object CatalogProviderText {
    private val castLink = Regex("""\[a=cr:\{.*?}/](.*?)\[/a]""", RegexOption.DOT_MATCHES_ALL)

    fun plain(value: String): String = castLink.replace(value) { it.groupValues[1].trim() }
}
