package com.wuming.tv.core.media

import com.wuming.tv.core.media.MediaSourceDescriptor
import com.wuming.tv.core.media.PlaybackTarget
import com.wuming.tv.core.media.SourceResolver
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Resolver for normalized direct HTTP sources. It performs no network I/O. */
class DirectHttpSourceResolver : SourceResolver {
    override fun adapterId(): String = DirectHttpInputDecoder.ADAPTER_ID

    override fun resolve(source: MediaSourceDescriptor): PlaybackTarget {
        if (adapterId() != source.adapterId || source.locatorVersion != 1) {
            throw IllegalArgumentException("Unsupported HTTP source version")
        }
        val value = source.locator["url"]
        val url = value?.toHttpUrlOrNull()
        if (url == null || url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw IllegalArgumentException("HTTP source locator is invalid")
        }
        return PlaybackTarget(url.newBuilder().fragment(null).build().toString(), emptyMap(), 0L)
    }
}
