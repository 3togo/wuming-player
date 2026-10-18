package com.wuming.android.tv.api.debug

import com.github.catvod.crawler.Spider

@FunctionalInterface
fun interface SpiderFactory {
    @Throws(Exception::class)
    fun create(): Spider?
}
