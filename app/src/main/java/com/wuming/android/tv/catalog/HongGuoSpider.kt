package com.wuming.android.tv.catalog

import android.content.Context
import com.github.catvod.crawler.Spider
import com.wuming.tv.core.catalog.CatVodCatalogJson
import com.wuming.tv.core.hongguo.*
import java.util.HashMap

/** Android lifecycle/JSON port for the shared HongGuo website adapter. */
class HongGuoSpider() : Spider() {
    internal constructor(value: HongGuoCatalogSourceExecutor) : this() { executor = value }
    private lateinit var executor: HongGuoCatalogSourceExecutor
    override fun init(context: Context, extend: String) {
        executor = HongGuoCatalogSourceExecutor(HongGuoSiteSpec(siteKey, "HongGuo"),
            HongGuoHttpTransport(newClientBuilder().build()))
    }
    override fun homeContent(filter: Boolean) = CatVodCatalogJson.home(executor.browseHome())
    override fun homeVideoContent() = CatVodCatalogJson.items(executor.home())
    override fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String>) =
        CatVodCatalogJson.page(executor.category(tid, pg.toIntOrNull() ?: 1, extend))
    override fun searchContent(key: String, quick: Boolean) = CatVodCatalogJson.items(executor.search(key))
    override fun searchContent(key: String, quick: Boolean, pg: String) =
        if (pg == "1") searchContent(key, quick) else CatVodCatalogJson.items(emptyList())
    override fun detailContent(ids: List<String>) = CatVodCatalogJson.detail(executor.detail(ids.first()))
    override fun playerContent(flag: String, id: String, vipFlags: List<String>) = CatVodCatalogJson.playback(executor.play(flag, id))
    override fun destroy() { if (::executor.isInitialized) executor.cancel() }
}
