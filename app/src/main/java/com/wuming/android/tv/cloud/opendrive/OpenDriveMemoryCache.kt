package com.wuming.android.tv.cloud.opendrive

import com.wuming.tv.core.cache.BoundedReferenceCache

internal class OpenDriveMemoryCache<K, V>(maxEntries: Int) : BoundedReferenceCache<K, V>(maxEntries)
