package com.wuming.android.tv.api.loader

import com.wuming.android.tv.App
import com.wuming.android.tv.utils.AppLog
import com.wuming.quickjs.crawler.Loader
import com.wuming.quickjs.utils.Module
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderNull
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

@JvmSuppressWildcards
open class JsLoader {
    companion object {
        private const val TAG = "JsLoader"

        private fun destroy(spider: Spider) {
            try {
                spider.destroy()
            } catch (e: Throwable) {
                AppLog.e(TAG, "Failed to release JavaScript provider", e)
            }
        }
    }

    private val spiders = ConcurrentHashMap<String, Spider>()
    private val loader = Loader()
    private val activeKeys = ConcurrentHashMap<String, String>()

    @Volatile private var recent: String? = null
    private var generation = 0L

    open fun clear() {
        val retired: List<Spider>
        synchronized(this) {
            generation++
            retired = ArrayList(spiders.values)
            spiders.clear()
            activeKeys.clear()
            recent = null
            Module.get().clear()
        }
        retired.forEach { destroy(it) }
    }

    open fun setRecent(recent: String?) {
        this.recent = recent
    }

    open fun getSpider(
        key: String,
        api: String,
        ext: String,
        jar: String,
    ): Spider {
        val cacheKey = JarLoader.spiderKey(jar, key, api, ext)
        val epoch: Long
        synchronized(this) {
            val existing = spiders[cacheKey]
            if (existing != null) { activeKeys[key] = cacheKey; return existing }
            epoch = generation
        }
        var created: Spider? = null
        try {
            created = loader.spider(api, BaseLoader.get().dex(jar))
            created.siteKey = com.wuming.tv.core.source.packages.PackageSourceIdentity.original(key)
            created.init(App.get(), ext)
            val winner = synchronized(this) { if (epoch == generation) spiders.putIfAbsent(cacheKey, created).also { activeKeys[key] = cacheKey } else SpiderNull() }
            if (winner == null) return created
            destroy(created)
            return winner
        } catch (e: Throwable) {
            if (created != null) destroy(created)
            AppLog.e(TAG, e)
            return SpiderNull()
        }
    }

    @Throws(Exception::class)
    open fun proxy(params: Map<String, String>): Array<Any?>? {
        val recent = recent ?: return null
        return activeKeys[recent]?.let { spiders[it]?.proxy(params) }
    }
}
