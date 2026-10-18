package com.wuming.android.tv.player.extractor

import java.io.File

internal object CacheTree {
    fun size(directory: File) = com.wuming.tv.core.cache.CacheTree.size(directory)
    fun delete(target: File) = com.wuming.tv.core.cache.CacheTree.delete(target)
    fun children(directory: File) = com.wuming.tv.core.cache.CacheTree.children(directory)
}
