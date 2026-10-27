package com.wuming.android.tv.catalog

import android.content.Context
import com.github.catvod.crawler.Spider
import com.wuming.tv.core.libvio.*
import java.util.HashMap

/** Android lifecycle and JSON port for the shared first-party adapter. */
class LibvioSpider : Spider() {
    private lateinit var executor: LibvioCatalogSourceExecutor
    override fun init(context: Context, extend: String) {
        executor = LibvioCatalogSourceExecutor(LibvioSiteSpec.from(siteKey, "Libvio", extend),
            LibvioHttpTransport(newClientBuilder().build()))
    }
    override fun homeContent(filter: Boolean) = LibvioCatVodProtocol.home(executor.browseHome())
    override fun homeVideoContent() = LibvioCatVodProtocol.items(executor.home())
    override fun categoryContent(tid: String, pg: String, filter: Boolean, extend: HashMap<String, String>) =
        LibvioCatVodProtocol.page(executor.category(tid, pg.toIntOrNull() ?: 1, extend))
    override fun searchContent(key: String, quick: Boolean) = LibvioCatVodProtocol.items(executor.search(key))
    override fun detailContent(ids: List<String>) = LibvioCatVodProtocol.detail(executor.detail(ids.first()))
    override fun playerContent(flag: String, id: String, vipFlags: List<String>) = LibvioCatVodProtocol.playback(executor.play(flag, id))
    override fun destroy() { if (::executor.isInitialized) executor.cancel() }
}
