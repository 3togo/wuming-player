package com.wuming.quickjs.crawler

import com.whl.quickjs.android.QuickJSLoader
import dalvik.system.DexClassLoader

class Loader {
    init {
        QuickJSLoader.init()
    }

    fun spider(api: String, dex: DexClassLoader): Spider = Spider(api, dex)
}
