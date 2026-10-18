package com.wuming.tv.core.base

/**
 * Fast byte[] → hex conversion without per-byte [String.format] overhead
 * (which rebuilds a Formatter and re-parses the pattern on every call).
 */
object Hex {
    private val LOWER = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )

    private val UPPER = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    )

    @JvmStatic
    fun encodeLower(data: ByteArray?): String = encode(data, LOWER)

    @JvmStatic
    fun encodeUpper(data: ByteArray?): String = encode(data, UPPER)

    /** Lowercase hex with `:` separators between bytes (MAC-address style). */
    @JvmStatic
    fun encodeUpperColon(data: ByteArray?): String {
        if (data == null || data.isEmpty()) return ""
        val sb = StringBuilder(data.size * 3)
        for (i in data.indices) {
            if (i > 0) sb.append(':')
            val v = data[i].toInt() and 0xff
            sb.append(UPPER[v ushr 4]).append(UPPER[v and 0xf])
        }
        return sb.toString()
    }

    private fun encode(data: ByteArray?, table: CharArray): String {
        if (data == null || data.isEmpty()) return ""
        val sb = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xff
            sb.append(table[v ushr 4]).append(table[v and 0xf])
        }
        return sb.toString()
    }
}
