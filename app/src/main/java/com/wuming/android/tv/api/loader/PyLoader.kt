package com.wuming.android.tv.api.loader

import com.wuming.android.tv.App
import com.wuming.android.tv.utils.AppLog
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderNull
import java.util.concurrent.ConcurrentHashMap

@JvmSuppressWildcards
open class PyLoader {
    companion object {
        private const val TAG = "PyLoader"
    }

    private val spiders = ConcurrentHashMap<String, Spider>()
    private val activeKeys = ConcurrentHashMap<String, String>()
    @Volatile private var recent: String? = null

    @Synchronized open fun clear() {
        spiders.values.forEach { runCatching { it.destroy() }.onFailure { error -> AppLog.e(TAG, error) } }
        spiders.clear()
        activeKeys.clear()
        recent = null
    }

    open fun setRecent(recent: String?) {
        this.recent = recent
    }

    open fun getSpider(
        key: String,
        api: String,
        ext: String,
    ): Spider {
        val cacheKey = JarLoader.spiderKey("", key, api, ext)
        val existing = spiders[cacheKey]
        if (existing != null && existing !is SpiderNull) { activeKeys[key] = cacheKey; return existing }
        synchronized(this) {
            val cached = spiders[cacheKey]
            if (cached != null && cached !is SpiderNull) { activeKeys[key] = cacheKey; return cached }
            try {
                val spider = com.wuming.android.tv.python.NativePythonSpider(key, api)
                spider.siteKey = com.wuming.tv.core.source.packages.PackageSourceIdentity.original(key)
                spider.init(App.get(), ext)
                spiders.putIfAbsent(cacheKey, spider)
                activeKeys[key] = cacheKey
                return spiders[cacheKey]!!
            } catch (e: Throwable) {
                AppLog.e(TAG, e)
                return SpiderNull()
            }
        }
    }

    @Throws(Exception::class)
    open fun proxy(params: Map<String, String>): Array<Any?>? {
        val recent = recent ?: return null
        return activeKeys[recent]?.let { spiders[it]?.proxy(params) }
    }
}
