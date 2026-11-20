package com.wuming.android.tv.api.loader

import com.github.catvod.crawler.Spider
import com.wuming.android.tv.App
import com.wuming.android.tv.utils.AppLog
import com.wuming.quickjs.crawler.Loader

@JvmSuppressWildcards
open class JsLoader {
    private val spiders = ScriptSpiders()
    private val loader = Loader()

    open fun clear() {
        spiders.clear()
        com.wuming.quickjs.utils.Module.get().clear()
    }
    fun trim() = spiders.trim()
    open fun setRecent(recent: String?) = spiders.setRecent(recent)

    open fun getSpider(key: String, api: String, ext: String, jar: String): Spider =
        spiders.reference(key, JarLoader.spiderKey(jar, key, api, ext)) {
            val spider = loader.spider(api, BaseLoader.get().dex(jar))
            try {
                spider.siteKey = com.wuming.tv.core.source.packages.PackageSourceIdentity.original(key)
                spider.init(App.get(), ext)
                spider
            } catch (failure: Throwable) {
                runCatching { spider.destroy() }.onFailure { AppLog.e("JsLoader", it) }
                throw failure
            }
        }

    @Throws(Exception::class)
    open fun proxy(params: MutableMap<String, String>): Array<Any?>? = spiders.proxy(params)

    @Throws(Exception::class)
    fun playbackProxy(params: MutableMap<String, String>, site: String): Array<Any?>? =
        spiders.proxy(params, site)
}
