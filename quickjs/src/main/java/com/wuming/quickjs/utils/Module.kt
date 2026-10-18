package com.wuming.quickjs.utils

import android.text.TextUtils
import android.util.LruCache
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Asset

class Module private constructor() {

    private val cache: LruCache<String, String> = object : LruCache<String, String>(MAX_SIZE_KB * 1024) {
        override fun sizeOf(key: String, value: String): Int = value.length
    }

    fun fetch(name: String): String? {
        var content = cache.get(name)
        if (!TextUtils.isEmpty(content)) return content
        if (name.startsWith("http")) {
            content = OkHttp.string(name)
            cache.put(name, content)
        } else if (name.startsWith("assets")) {
            content = Asset.read(name)
            cache.put(name, content)
        } else if (name.startsWith("lib/")) {
            content = Asset.read("js/$name")
            cache.put(name, content)
        }
        return content
    }

    fun clear() = cache.evictAll()

    companion object {
        private const val MAX_SIZE_KB = 512

        @Volatile
        private var INSTANCE: Module? = null

        @JvmStatic
        fun get(): Module =
            INSTANCE ?: synchronized(this) { INSTANCE ?: Module().also { INSTANCE = it } }
    }
}
