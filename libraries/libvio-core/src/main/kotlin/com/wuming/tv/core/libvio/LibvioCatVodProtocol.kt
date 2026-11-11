package com.wuming.tv.core.libvio

import com.wuming.tv.core.catalog.*

/** Compatibility surface for the shared CatVod catalogue serializer. */
object LibvioCatVodProtocol {
    fun home(value: CatalogHome) = CatVodCatalogJson.home(value)
    fun page(value: CatalogPage) = CatVodCatalogJson.page(value)
    fun items(value: List<CatalogItem>) = CatVodCatalogJson.items(value)
    fun detail(value: CatalogDetail) = CatVodCatalogJson.detail(value)
    fun playback(value: CatalogPlayback) = CatVodCatalogJson.playback(value)
}
