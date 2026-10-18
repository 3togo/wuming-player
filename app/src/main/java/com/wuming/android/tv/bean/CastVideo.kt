package com.wuming.android.tv.bean

import com.wuming.android.tv.server.Server
import com.wuming.android.tv.utils.AppLog
import com.github.catvod.utils.Path
import com.github.catvod.utils.Util

class CastVideo(
    val name: String?,
    url: String?,
    position: Long,
    duration: Long,
    val headers: Map<String, String>?,
    val nextName: String?,
    val nextUrl: String?,
    val siteKey: String?,
    val flag: String?,
    val audioTrackFormat: String?
) {
    val url: String? = normalizeUrl(url)
    val position: Long = maxOf(position, 0)
    val duration: Long = maxOf(duration, 0)

    fun name(): String? = name
    fun url(): String? = url
    fun position(): Long = position
    fun duration(): Long = duration
    fun headers(): Map<String, String>? = headers
    fun nextName(): String? = nextName
    fun nextUrl(): String? = nextUrl
    fun siteKey(): String? = siteKey
    fun flag(): String? = flag
    fun audioTrackFormat(): String? = audioTrackFormat

    override fun equals(other: Any?): Boolean = other is CastVideo &&
        name == other.name && url == other.url && position == other.position &&
        duration == other.duration && headers == other.headers && nextName == other.nextName &&
        nextUrl == other.nextUrl && siteKey == other.siteKey && flag == other.flag &&
        audioTrackFormat == other.audioTrackFormat

    override fun hashCode(): Int =
        listOf(name, url, position, duration, headers, nextName, nextUrl, siteKey, flag, audioTrackFormat).hashCode()

    constructor(name: String?, url: String?, position: Long, headers: Map<String, String>?) :
        this(name, url, position, 0, headers, null, null, null, null, null)

    constructor(name: String?, url: String?, position: Long, headers: Map<String, String>?, nextName: String?, nextUrl: String?) :
        this(name, url, position, 0, headers, nextName, nextUrl, null, null, null)

    constructor(name: String?, url: String?, position: Long, headers: Map<String, String>?, nextName: String?, nextUrl: String?, siteKey: String?, flag: String?) :
        this(name, url, position, 0, headers, nextName, nextUrl, siteKey, flag, null)

    constructor(name: String?, url: String?, position: Long, duration: Long, headers: Map<String, String>?, nextName: String?, nextUrl: String?, siteKey: String?, flag: String?) :
        this(name, url, position, duration, headers, nextName, nextUrl, siteKey, flag, null)

    companion object {
        private fun normalizeUrl(rawUrl: String?): String? {
            if (rawUrl == null) return null
            var url = rawUrl
            val originalUrl = url
            if (url.startsWith("file://")) {
                var relativePath = url.substring("file://".length)
                if (relativePath.startsWith(Path.rootPath())) {
                    relativePath = relativePath.substring(Path.rootPath().length)
                }
                url = Server.get().getAddress() + "/" + relativePath
            }
            val phoneIp = Util.getIp()
            val containsLocalhost = url.contains("127.0.0.1")
            if (containsLocalhost) {
                url = url.replace("127.0.0.1", phoneIp)
            }
            AppLog.d("CastVideo", String.format("normalizeUrl: original=%s, phoneIp=%s, containsLocalhost=%b, result=%s",
                originalUrl, phoneIp, containsLocalhost, url))
            return url
        }
    }
}
