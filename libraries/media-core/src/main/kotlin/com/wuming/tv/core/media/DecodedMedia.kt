package com.wuming.tv.core.media

/** Side-effect-free normalized result returned by an input decoder. */
class DecodedMedia(
    val kind: OpenDriveUri.Kind,
    title: String?,
    sources: List<MediaSourceDescriptor>?
) {
    val title: String = (title ?: "").trim().let { if (it.length > 512) it.substring(0, 512) else it }
    val sources: List<MediaSourceDescriptor> = sources?.toList() ?: emptyList()

    init {
        if (this.sources.isEmpty()) throw IllegalArgumentException("Decoded media source is required")
    }

    @JvmName("kind")
    fun kindAccessor(): OpenDriveUri.Kind = kind
    @JvmName("title")
    fun titleAccessor(): String = title
    @JvmName("sources")
    fun sourcesAccessor(): List<MediaSourceDescriptor> = sources

    fun persistable(): Boolean = sources.all { it.persistable }
}
