package com.wuming.tv.core.cloud

import java.util.Collections
import java.util.LinkedHashMap

/** A short-lived playback resource returned by a reviewed built-in provider. */
class OpenDrivePlayback(
    url: String,
    headers: Map<String, String>?,
    expiresAtEpochMillis: Long
) {
    val url: String = url
    val headers: Map<String, String> = if (headers == null)
        emptyMap() else Collections.unmodifiableMap(LinkedHashMap(headers))
    val expiresAtEpochMillis: Long = maxOf(0L, expiresAtEpochMillis)

    init {
        if (this.url.isBlank()) throw IllegalArgumentException("Playback URL is required")
    }

    @JvmName("url")
    fun urlAccessor(): String = url
    @JvmName("headers")
    fun headersAccessor(): Map<String, String> = headers
    @JvmName("expiresAtEpochMillis")
    fun expiresAtEpochMillisAccessor(): Long = expiresAtEpochMillis
}
