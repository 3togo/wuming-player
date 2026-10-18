package com.wuming.tv.core.media

import java.util.Locale

object MediaFileClassifier {

    private val videoExtensions = setOf(
        "264", "3g2", "3gp", "3gpp", "amv", "asf", "asx", "av1", "avc", "avi", "bik",
        "d2v", "dat", "dif", "divx", "dv", "dvr-ms", "evo", "f4p", "f4v", "flv", "h261",
        "h262", "h263", "h264", "h265", "hevc", "ismv", "ivf", "m1v", "m2p", "m2t", "m2ts",
        "m2v", "m3u8", "m4p", "m4v", "mjpeg", "mjpg", "mkv", "mov", "mp2v", "mp4", "mp4v",
        "mpe", "mpeg", "mpg", "mpv", "mt2s", "mts", "nsv", "nut", "ogm", "ogv", "pva",
        "qt", "rm", "rmvb", "rv", "smk", "swf", "tp", "trp", "ts", "ty", "vob", "vro",
        "webm", "wmv", "wtv", "xvid", "y4m",
    )

    private val cloudExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "m2ts",
    )

    @JvmStatic
    fun isVideoExtension(extension: String?): Boolean {
        val normalized = extension?.trim()?.removePrefix(".")?.lowercase(Locale.ROOT)
        return !normalized.isNullOrEmpty() && normalized in videoExtensions
    }

    @JvmStatic
    fun isVideoFileName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val separator = name.substringAfterLast('/').substringAfterLast('\\')
        if (!separator.contains('.')) return false
        return isVideoExtension(separator.substringAfterLast('.'))
    }

    /**
     * Strict allow-list for cloud drive listings, deliberately narrower than the
     * playback set: drives surface plain container files only, never raw streams,
     * playlists or transport files. Takes the name as-is, so a trailing blank
     * after the extension does not match.
     */
    @JvmStatic
    fun isCloudVideoFileName(name: String?): Boolean {
        if (name.isNullOrEmpty()) return false
        val dot = name.lastIndexOf('.')
        if (dot < 0) return false
        return name.substring(dot + 1).lowercase(Locale.ROOT) in cloudExtensions
    }

    @JvmStatic
    fun cloudVideoExtensions(): Set<String> = cloudExtensions
}
