package com.wuming.tv.core.catalog

/**
 * Platform-neutral catalogue execution boundary.
 *
 * A gateway may execute a desktop-compatible source locally or delegate to an
 * Android companion. Callers must not infer where provider code runs.
 */
interface CatalogGateway {

    fun sources(): List<CatalogSource>

    /** Initial catalogue cards for a source. Backends without a home feed may return an empty list. */
    fun home(source: String): List<CatalogItem> = emptyList()

    /** Home cards plus provider-defined media types and filters. */
    fun browseHome(source: String): CatalogHome = CatalogHome(home(source))

    fun category(
        source: String,
        typeId: String,
        page: Int = 1,
        filters: Map<String, String> = emptyMap(),
    ): CatalogPage = CatalogPage()

    fun search(source: String, query: String): List<CatalogItem>

    fun detail(source: String, id: String): CatalogDetail

    fun play(source: String, flag: String, id: String): CatalogPlayback
}
