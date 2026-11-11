package com.wuming.tv.core.playback

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AvcSpsParserTest {
    @Test fun readsMeasuredProgrammeAndBothAdvertisingCodecParameterSets() {
        val samples = listOf(
            "AAABZ2QAH6zZQFAFuwFqAgICgAAAAwCAAAAZB4wYywAAAAFo74/L" to (1280 to 720),
            "AAABZ2QAMqzZgHgCJ+XAWoCAgKAAAAMAIAAAB4HjBjNAAAAAAWjpePLIsA==" to (1920 to 1080),
            "AAABZ2QAMqzZgHgCJ+XAWoCAgKAAAAMAIAAABkHjBjNAAAAAAWjpePLIsA==" to (1920 to 1080))
        for ((index, sample) in samples.withIndex()) {
            val (encoded, expected) = sample
            val bytes = Base64.getDecoder().decode(encoded)
            val end = (4 until bytes.size - 3).first { bytes[it] == 0.toByte() && bytes[it + 1] == 0.toByte() && bytes[it + 2] == 1.toByte() }
            val info = AvcSpsParser.parseSps(bytes, 3, end)
            assertEquals(expected.first, info.width)
            assertEquals(expected.second, info.height)
            assertEquals(listOf(25.0, 30.0, 25.0)[index], info.nominalFrameRate, 0.001)
        }
    }

    @Test fun handlesChromaCroppingInterlacingAndSeparateColourPlanes() {
        for (chroma in 0..3) for (interlaced in listOf(false, true)) {
            val bytes = sps(chroma, interlaced, false)
            val info = AvcSpsParser.parseSps(bytes, 0, bytes.size)
            val cropX = if (chroma in 1..2) 2 else 1
            val cropY = (if (chroma == 1) 2 else 1) * (if (interlaced) 2 else 1)
            assertEquals(160 - 2 * cropX, info.width)
            assertEquals(80 * (if (interlaced) 2 else 1) - 2 * cropY, info.height)
            assertEquals(0.0, info.nominalFrameRate, 0.0)
        }
        val bytes = sps(3, true, true)
        val info = AvcSpsParser.parseSps(bytes, 0, bytes.size)
        assertEquals(158, info.width)
        assertEquals(156, info.height)
    }

    @Test fun rejectsInvalidBoundsTruncatedParameterSetsAndUnboundedGolombValues() {
        assertThrows(IllegalArgumentException::class.java) { AvcSpsParser.parseSps(byteArrayOf(), 0, 1) }
        val bytes = sps(1, false, false)
        assertThrows(IllegalArgumentException::class.java) { AvcSpsParser.parseSps(bytes, 0, 4) }
        assertThrows(IllegalArgumentException::class.java) { AvcSpsParser.parseSps(ByteArray(64).also { it[0] = 0x67 }, 0, 64) }
    }

    @Test fun readsUnsignedTimingAfterOptionalVuiFieldsAndKeepsDimensionsWhenTimingIsUnknown() {
        for ((ticks, scale) in listOf(1001L to 60000L, 50_000_000L to 3_000_000_000L, 0L to 50L)) {
            val bytes = sps(1, false, false, ticks to scale)
            val info = AvcSpsParser.parseSps(bytes, 0, bytes.size)
            assertEquals(if (ticks == 0L) 0.0 else scale / (2.0 * ticks), info.nominalFrameRate, 0.000001)
            var cutoff = bytes.size - 6
            // Truncate optional timing, without leaving a dangling emulation-
            // prevention byte (which correctly invalidates the whole NAL).
            if (bytes[cutoff - 1] == 3.toByte() && bytes[cutoff - 2] == 0.toByte() && bytes[cutoff - 3] == 0.toByte()) cutoff--
            val truncated = bytes.copyOf(cutoff)
            val partial = AvcSpsParser.parseSps(truncated, 0, truncated.size)
            assertEquals(info.width, partial.width)
            assertEquals(info.height, partial.height)
            assertEquals(0.0, partial.nominalFrameRate, 0.0)
        }
        val interlaced = sps(1, true, false, 1L to 50L)
        assertEquals(0.0, AvcSpsParser.parseSps(interlaced, 0, interlaced.size).nominalFrameRate, 0.0)
    }

    private fun sps(chroma: Int, interlaced: Boolean, separate: Boolean, timing: Pair<Long, Long>? = null): ByteArray {
        val bits = StringBuilder()
        fun bit(value: Boolean) { bits.append(if (value) '1' else '0') }
        fun fixed(value: Int, count: Int) { bits.append(value.toString(2).padStart(count, '0')) }
        fun ue(value: Int) { val code = (value + 1).toString(2); bits.append("0".repeat(code.length - 1)); bits.append(code) }
        fixed(100, 8); fixed(0, 8); fixed(30, 8); ue(0); ue(chroma)
        if (chroma == 3) bit(separate)
        ue(0); ue(0); bit(false); bit(false); ue(0); ue(0); ue(0); ue(1); bit(false)
        ue(9); ue(4); bit(!interlaced); if (interlaced) bit(false)
        bit(true); bit(true); repeat(4) { ue(1) }; bit(timing != null)
        if (timing != null) {
            bit(true); fixed(255, 8); fixed(1, 16); fixed(1, 16) // extended SAR
            bit(true); bit(false) // overscan
            bit(true); fixed(5, 3); bit(false); bit(true); repeat(3) { fixed(1, 8) }
            bit(true); ue(0); ue(0) // chroma location
            bit(true)
            for (value in listOf(timing.first, timing.second)) bits.append(value.toString(2).padStart(32, '0'))
            bit(false) // variable-frame-rate timing remains usable as a nominal signal
        }
        bit(true)
        while (bits.length % 8 != 0) bit(false)
        val rbsp = bits.toString().chunked(8).map { it.toInt(2).toByte() }
        val escaped = mutableListOf<Byte>()
        var zeros = 0
        for (byte in rbsp) {
            if (zeros >= 2 && (byte.toInt() and 255) <= 3) { escaped.add(3); zeros = 0 }
            escaped.add(byte)
            zeros = if (byte == 0.toByte()) zeros + 1 else 0
        }
        return byteArrayOf(0x67) + escaped.toByteArray()
    }
}
