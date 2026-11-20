package com.wuming.android.tv.api.loader

import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderNull
import com.wuming.android.tv.utils.AppLog
import com.wuming.tv.core.cache.LeasedResourceCache
import com.wuming.tv.core.cache.MemoryBudget
import java.util.concurrent.atomic.AtomicLong

/** Keeps the CatVod ABI at the edge while the runtime owner leases each initialized instance. */
internal class ScriptSpiders {
    private data class Key(val site: String, val original: String, val identity: String)

    private val cache = LeasedResourceCache<Key, Spider>(MemoryBudget.current().scale(4, 8, 16)) { spider ->
        runCatching { spider.destroy() }.onFailure { AppLog.e("ScriptSpiders", it) }
    }

    private val playbackRequest = AtomicLong()

    @Volatile private var recent: String? = null
    @Volatile private var recentKey: Key? = null

    fun setRecent(site: String?) {
        recent = site
        if (recentKey?.site != site) recentKey = null
    }

    fun clear() {
        recent = null
        recentKey = null
        cache.clear()
    }

    fun trim() = cache.trim()

    fun proxy(parameters: MutableMap<String, String>, explicitSite: String? = null): Array<Any?>? =
        cache.useExisting(recentKey.takeIf { explicitSite == null }, {
            explicitSite == null || explicitSite == it.site || explicitSite == it.original
        }) { it.proxy(parameters) }

    fun reference(site: String, identity: String, create: () -> Spider): Spider {
        val key = Key(site, com.wuming.tv.core.source.packages.PackageSourceIdentity.original(site), identity)
        return object : Spider() {
            init { siteKey = key.original }

            private fun <R> call(operation: (Spider) -> R): R = try {
                cache.use(key, { initialize(create) }) { spider, _ ->
                    if (recent == site) recentKey = key
                    operation(spider)
                }
            } catch (_: InitializationFailure) {
                operation(SpiderNull())
            }

            private fun playback(operation: (Spider) -> String?): String? {
                val request = playbackRequest.incrementAndGet()
                return try {
                    cache.use(key, { initialize(create) }) { spider, keep ->
                        if (recent == site) recentKey = key
                        operation(spider).also {
                            if (!it.isNullOrBlank() && playbackRequest.get() == request) keep()
                        }
                    }
                } catch (_: InitializationFailure) {
                    operation(SpiderNull())
                }
            }

            override fun homeContent(filter: Boolean) = call { it.homeContent(filter) }
            override fun homeVideoContent() = call { it.homeVideoContent() }
            override fun categoryContent(tid: String?, pg: String?, filter: Boolean, extend: HashMap<String, String>?) =
                call { it.categoryContent(tid, pg, filter, extend) }
            override fun detailContent(ids: MutableList<String>?) = call { it.detailContent(ids) }
            override fun searchContent(key: String?, quick: Boolean) = call { it.searchContent(key, quick) }
            override fun searchContent(key: String?, quick: Boolean, pg: String?) = call { it.searchContent(key, quick, pg) }
            override fun playerContent(flag: String?, id: String?, vipFlags: MutableList<String>?) =
                playback { it.playerContent(flag, id, vipFlags) }
            override fun liveContent(url: String?) = playback { it.liveContent(url) }
            override fun manualVideoCheck() = call { it.manualVideoCheck() }
            override fun isVideoFormat(url: String?) = call { it.isVideoFormat(url) }
            override fun proxy(params: MutableMap<String, String>?) = call { it.proxy(params) }
            override fun action(action: String?) = call { it.action(action) }
        }
    }

    private class InitializationFailure(cause: Throwable) : Exception(cause)

    private fun initialize(create: () -> Spider): Spider = try {
        create()
    } catch (failure: Throwable) {
        if (failure !is Exception && failure !is LinkageError) throw failure
        AppLog.e("ScriptSpiders", failure)
        throw InitializationFailure(failure)
    }
}
