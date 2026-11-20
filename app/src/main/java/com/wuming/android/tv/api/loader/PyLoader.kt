package com.wuming.android.tv.api.loader

import com.github.catvod.crawler.Spider
import com.wuming.android.tv.App
import com.wuming.android.tv.python.NativePythonSpider

@JvmSuppressWildcards
open class PyLoader {
    private val spiders = ScriptSpiders()

    open fun clear() = spiders.clear()
    fun trim() = spiders.trim()
    open fun setRecent(recent: String?) = spiders.setRecent(recent)

    open fun getSpider(key: String, api: String, ext: String): Spider =
        spiders.reference(key, JarLoader.spiderKey("", key, api, ext)) {
            NativePythonSpider(key, api).apply {
                siteKey = com.wuming.tv.core.source.packages.PackageSourceIdentity.original(key)
                init(App.get(), ext)
            }
        }

    @Throws(Exception::class)
    open fun proxy(params: MutableMap<String, String>): Array<Any?>? = spiders.proxy(params)

    @Throws(Exception::class)
    fun playbackProxy(params: MutableMap<String, String>, site: String): Array<Any?>? =
        spiders.proxy(params, site)
}
