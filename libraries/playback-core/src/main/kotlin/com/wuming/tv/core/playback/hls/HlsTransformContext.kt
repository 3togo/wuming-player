package com.wuming.tv.core.playback.hls

import java.util.function.Function

class HlsTransformContext(
    playlistUrl: String?,
    uriMapper: Function<String, String>?,
    attributes: Map<String, String>?
) {
    val playlistUrl: String = playlistUrl ?: ""
    val uriMapper: Function<String, String> = uriMapper ?: Function.identity()
    val attributes: Map<String, String> = java.util.Map.copyOf(attributes ?: emptyMap())

    fun playlistUrl(): String = playlistUrl
    fun uriMapper(): Function<String, String> = uriMapper
    fun attributes(): Map<String, String> = attributes

    override fun equals(other: Any?): Boolean =
        other is HlsTransformContext && playlistUrl == other.playlistUrl &&
        uriMapper == other.uriMapper &&
        attributes == other.attributes

    override fun hashCode(): Int = listOf(playlistUrl, uriMapper, attributes).hashCode()

    override fun toString(): String = "HlsTransformContext(playlistUrl=$playlistUrl, uriMapper=$uriMapper, attributes=$attributes)"

    companion object {
        @JvmStatic
        fun of(playlistUrl: String): HlsTransformContext =
            HlsTransformContext(playlistUrl, Function.identity(), emptyMap())
    }
}
