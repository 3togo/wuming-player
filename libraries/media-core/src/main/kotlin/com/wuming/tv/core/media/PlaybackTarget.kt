package com.wuming.tv.core.media

/** Final protocol-neutral hand-off to the existing player. */
class PlaybackTarget(
    url: String,
    headers: Map<String, String>?,
    expiresAtEpochMs: Long
) {
    val url: String = url
    val headers: Map<String, String> = if (headers == null) emptyMap() else headers.toMap()
    val expiresAtEpochMs: Long = maxOf(0L, expiresAtEpochMs)

    init {
        if (url.isBlank()) throw IllegalArgumentException("Playback URL is empty")
    }

    @JvmName("url")
    fun urlAccessor(): String = url
    @JvmName("headers")
    fun headersAccessor(): Map<String, String> = headers
    @JvmName("expiresAtEpochMs")
    fun expiresAtEpochMsAccessor(): Long = expiresAtEpochMs
}
