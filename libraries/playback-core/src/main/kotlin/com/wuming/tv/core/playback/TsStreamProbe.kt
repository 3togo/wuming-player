package com.wuming.tv.core.playback

import com.wuming.tv.core.base.native.NativeLoader
import java.io.ByteArrayOutputStream

/**
 * Reads AVC dimensions and the first raw video PTS from a bounded MPEG-TS prefix.
 * Platform-neutral: SPS NAL unit parsing is delegated to [SpsParserPort].
 */
class TsStreamProbe private constructor(private val spsParser: SpsParserPort) {

    interface SpsParserPort {
        @Throws(Exception::class)
        fun parseSps(data: ByteArray, offset: Int, end: Int): HlsResolutionAdFilter.VideoInfo
    }

    private val nativeAvailable: Boolean = NativeLoader.available

    private external fun nativeProbe(data: ByteArray): DoubleArray?

    fun read(data: ByteArray?): HlsResolutionAdFilter.VideoInfo? {
        if (data == null || data.size < 188 || data.size > MAX_BYTES) return null
        if (nativeAvailable) {
            val result = nativeProbe(data)
            if (result != null) {
                return HlsResolutionAdFilter.VideoInfo(result[0].toInt(), result[1].toInt(), result[2])
            }
        }
        try {
            var videoPid = -1
            var pts = -1.0
            val elementary = ByteArrayOutputStream()
            for (packet in 0..(data.size - 188) step 188) {
                val end = packet + 188
                if (u(data, packet) != 0x47 || (u(data, packet + 1) and 0x80) != 0) return null
                val pid = ((u(data, packet + 1) and 31) shl 8) or u(data, packet + 2)
                val control = u(data, packet + 3)
                if ((control and 0xc0) != 0) return null
                if ((control and 0x10) == 0) continue
                var pos = packet + 4
                if ((control and 0x20) != 0) pos += 1 + u(data, pos)
                if (pos >= end) continue
                val start = (u(data, packet + 1) and 0x40) != 0
                if (videoPid < 0 && start) {
                    val table = pos + 1 + u(data, pos)
                    if (table + 12 <= end && u(data, table) == 2 && (u(data, table + 1) and 0x80) != 0) {
                        val sectionEnd = table + 3 + (((u(data, table + 1) and 15) shl 8) or u(data, table + 2)) - 4
                        if (sectionEnd > end) continue
                        var entry = table + 12 + (((u(data, table + 10) and 15) shl 8) or u(data, table + 11))
                        while (entry + 5 <= sectionEnd) {
                            if (u(data, entry) == 0x1b) {
                                videoPid = ((u(data, entry + 1) and 31) shl 8) or u(data, entry + 2)
                                break
                            }
                            entry += 5 + (((u(data, entry + 3) and 15) shl 8) or u(data, entry + 4))
                        }
                    }
                }
                if (pid != videoPid) continue
                if (start) {
                    if (pos + 9 > end || u(data, pos) != 0 || u(data, pos + 1) != 0 ||
                        u(data, pos + 2) != 1 || (u(data, pos + 3) and 0xf0) != 0xe0
                    ) {
                        return null
                    }
                    if (pts < 0) {
                        if ((u(data, pos + 7) and 0x80) == 0 || u(data, pos + 8) < 5 || pos + 14 > end) return null
                        val p = pos + 9
                        if ((u(data, p) and 1) == 0 || (u(data, p + 2) and 1) == 0 || (u(data, p + 4) and 1) == 0) return null
                        val ticks =
                            ((u(data, p) and 14).toLong() shl 29) or (u(data, p + 1).toLong() shl 22) or
                                ((u(data, p + 2) and 254).toLong() shl 14) or (u(data, p + 3) shl 7).toLong() or
                                (u(data, p + 4) shr 1).toLong()
                        pts = ticks / 90000.0
                    }
                    pos += 9 + u(data, pos + 8)
                    if (pos > end) return null
                }
                elementary.write(data, pos, end - pos)
            }
            if (pts < 0) return null
            val bytes = elementary.toByteArray()
            for (i in 0 until bytes.size - 4) {
                if (bytes[i].toInt() != 0 || bytes[i + 1].toInt() != 0 || bytes[i + 2].toInt() != 1 ||
                    (bytes[i + 3].toInt() and 31) != 7
                ) {
                    continue
                }
                var nalEnd = i + 4
                while (nalEnd + 3 < bytes.size &&
                    !(
                        bytes[nalEnd].toInt() == 0 && bytes[nalEnd + 1].toInt() == 0 &&
                            (bytes[nalEnd + 2].toInt() == 1 || (bytes[nalEnd + 2].toInt() == 0 && bytes[nalEnd + 3].toInt() == 1))
                    )
                ) {
                    nalEnd++
                }
                if (nalEnd + 3 >= bytes.size) return null
                val sps = spsParser.parseSps(bytes, i + 3, nalEnd)
                return HlsResolutionAdFilter.VideoInfo(sps.width, sps.height, pts)
            }
        } catch (_: RuntimeException) {
        }
        return null
    }

    companion object {
        const val MAX_BYTES = 64 * 1024

        @JvmStatic
        fun create(spsParser: SpsParserPort): TsStreamProbe = TsStreamProbe(spsParser)

        @JvmStatic
        fun create(): TsStreamProbe = TsStreamProbe(AvcSpsParser)

        private fun u(data: ByteArray, offset: Int): Int = data[offset].toInt() and 255
    }
}
