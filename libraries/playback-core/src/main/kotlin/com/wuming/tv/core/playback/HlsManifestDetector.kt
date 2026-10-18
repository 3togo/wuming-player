package com.wuming.tv.core.playback

import java.nio.charset.StandardCharsets

/** Platform-neutral HLS manifest and media detection utilities shared by Android and Desktop. */
object HlsManifestDetector {

    private const val MPEG_TS_SYNC = 0x47
    private const val MPEG_TS_PACKET_BYTES = 188
    private const val REQUIRED_SYNC_PACKETS = 4

    /** Returns true if the bytes look like an HLS playlist (master or media), tolerating BOM and leading whitespace. */
    @JvmStatic
    fun isManifest(bytes: ByteArray): Boolean {
        var offset = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte()
            && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        while (offset < bytes.size) {
            when (bytes[offset].toInt()) {
                32, 13, 10, 9 -> offset++
                else -> break
            }
        }
        return bytes.size - offset >= 7 &&
            "#EXTM3U".indices.all { bytes[offset + it].toInt() == "#EXTM3U"[it].code }
    }

    /** Returns the byte offset where the MPEG-TS sync byte (0x47) first appears,
     *  requiring multiple consecutive sync packets, or -1 if not found. */
    @JvmStatic
    fun transportStreamOffset(bytes: ByteArray): Int {
        val limit = minOf(bytes.size, 4096)
        for (offset in 0 until limit) {
            if (bytes[offset].toInt() and 0xff != MPEG_TS_SYNC) continue
            if ((1 until REQUIRED_SYNC_PACKETS).all { packet ->
                    val index = offset + packet * MPEG_TS_PACKET_BYTES
                    index < bytes.size && bytes[index].toInt() and 0xff == MPEG_TS_SYNC
                }
            ) return offset
        }
        return -1
    }

    /** Heuristic: checks if the bytes look like an HTML/XML error document or JSON error response. */
    @JvmStatic
    fun looksLikeErrorDocument(bytes: ByteArray): Boolean {
        val prefix = String(bytes, 0, minOf(bytes.size, 1024), StandardCharsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\r', '\n', '\t')
            .take(512)
            .lowercase()
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html") ||
            prefix.startsWith("<body") || prefix.startsWith("{") ||
            prefix.contains("403 forbidden") || prefix.contains("access denied")
    }

    /** Counts the number of segment URI lines (lines starting with #EXTINF:) in an HLS playlist. */
    @JvmStatic
    fun segmentCount(manifest: String): Int =
        manifest.lineSequence().count { it.startsWith("#EXTINF:") }
}
