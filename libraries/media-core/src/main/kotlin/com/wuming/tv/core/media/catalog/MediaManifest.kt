package com.wuming.tv.core.media.catalog

import com.wuming.tv.core.media.MediaSourceDescriptor
import com.wuming.tv.core.media.OpenDriveUri

/** Provider-neutral, versioned description of one catalog resource. */
class MediaManifest(
    val uri: OpenDriveUri,
    title: String?,
    val version: Int,
    sources: List<MediaSourceDescriptor>?
) {
    val title: String = title ?: ""
    val sources: List<MediaSourceDescriptor> = sources?.toList() ?: emptyList()

    init {
        if (version < 1) throw IllegalArgumentException("Manifest version is invalid")
    }

    @JvmName("uri")
    fun uriAccessor(): OpenDriveUri = uri
    @JvmName("title")
    fun titleAccessor(): String = title
    @JvmName("version")
    fun versionAccessor(): Int = version
    @JvmName("sources")
    fun sourcesAccessor(): List<MediaSourceDescriptor> = sources
}
