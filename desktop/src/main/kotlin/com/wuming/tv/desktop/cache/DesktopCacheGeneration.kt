package com.wuming.tv.desktop.cache

import java.util.concurrent.atomic.AtomicLong

/** Invalidates disposable in-memory data on account changes or cache clearing without putting credentials into cache keys. */
internal object DesktopCacheGeneration {
    val value = AtomicLong()
}
