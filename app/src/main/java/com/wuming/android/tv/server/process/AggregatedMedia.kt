package com.wuming.android.tv.server.process

/** Byte-exact description of one playlist's aggregated TS: cumulative segment offsets. */
class AggregatedMedia(
    @JvmField val segments: List<String>,
    sizes: LongArray,
) {
    @JvmField val starts: LongArray = LongArray(sizes.size + 1)
    @JvmField val total: Long

    init {
        var acc = 0L
        for (i in sizes.indices) {
            starts[i] = acc
            acc += sizes[i]
        }
        starts[sizes.size] = acc
        total = acc
    }

    /** Index of the segment containing the absolute byte position. */
    fun segmentFor(position: Long): Int {
        var lo = 0
        var hi = segments.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= position) lo = mid else hi = mid - 1
        }
        return lo
    }
}
