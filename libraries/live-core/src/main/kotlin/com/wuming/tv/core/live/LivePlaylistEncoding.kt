package com.wuming.tv.core.live

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/** Decodes text playlists saved with a Unicode byte order mark. */
object LivePlaylistEncoding {
    @JvmStatic
    fun stripMark(text: String): String = text.removePrefix("\uFEFF")

    @JvmStatic
    fun decode(bytes: ByteArray): String {
        val prefix = when {
            bytes.startsWith(0x00, 0x00, 0xFE, 0xFF) -> 4 to Charset.forName("UTF-32BE")
            bytes.startsWith(0xFF, 0xFE, 0x00, 0x00) -> 4 to Charset.forName("UTF-32LE")
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> 3 to StandardCharsets.UTF_8
            bytes.startsWith(0xFE, 0xFF) -> 2 to StandardCharsets.UTF_16BE
            bytes.startsWith(0xFF, 0xFE) -> 2 to StandardCharsets.UTF_16LE
            else -> 0 to StandardCharsets.UTF_8
        }
        return stripMark(String(bytes, prefix.first, bytes.size - prefix.first, prefix.second))
    }

    private fun ByteArray.startsWith(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { index -> this[index].toInt() and 0xFF == values[index] }
}
