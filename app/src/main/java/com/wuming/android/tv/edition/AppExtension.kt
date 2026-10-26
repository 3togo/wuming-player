package com.wuming.android.tv.edition

import com.github.catvod.utils.Path
import com.wuming.android.tv.BuildConfig
import com.wuming.android.tv.bean.Episode
import com.wuming.android.tv.player.Source
import com.wuming.android.tv.player.extractor.CacheTree
import com.wuming.android.tv.server.impl.Process
import java.util.concurrent.Callable

interface AppExtension {
    fun beforeLocalExtractors(): List<Source.Extractor> = emptyList()
    fun afterLocalExtractors(): List<Source.Extractor> = emptyList()
    fun episodeParser(url: String): Callable<List<Episode>>? = null
    fun serverProcesses(): List<Process> = emptyList()
    fun initialize() {}

    /** Worker-thread operation. A non-null message describes a deferred deletion. */
    fun clearCache(): String? {
        CacheTree.children(Path.cache()).forEach { CacheTree.delete(it) }
        return null
    }
}

object AppExtensions {
    @JvmStatic val current: AppExtension by lazy { load(BuildConfig.EDITION_EXTENSION) }

    internal fun load(className: String): AppExtension = if (className.isEmpty()) {
        object : AppExtension {}
    } else {
        // A broken configured extension must fail visibly, never silently lose features.
        Class.forName(className).asSubclass(AppExtension::class.java).getDeclaredConstructor().newInstance()
    }
}
