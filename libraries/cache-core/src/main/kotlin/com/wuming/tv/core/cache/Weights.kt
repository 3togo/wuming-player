package com.wuming.tv.core.cache

/** Measurement helpers for caches whose budget is expressed in retained bytes. */
object Weights {

    /** Retained bytes of a serialized payload. */
    @JvmStatic
    fun bytes(data: ByteArray?): Long = if (data == null) 0L else data.size.toLong()

    /** Bytes a [CharSequence] occupies once encoded as UTF-8, without allocating. */
    @JvmStatic
    fun utf8(text: CharSequence?): Long {
        if (text == null) return 0L
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val current = text[i]
            if (current.code < 0x80) {
                bytes += 1
            } else if (current.code < 0x800) {
                bytes += 2
            } else if (Character.isHighSurrogate(current) && i + 1 < text.length
                && Character.isLowSurrogate(text[i + 1])
            ) {
                bytes += 4
                i++
            } else {
                bytes += 3
            }
            i++
        }
        return bytes
    }
}
