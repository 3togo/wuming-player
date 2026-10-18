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
        for ((encoded, expected) in samples) {
            val bytes = Base64.getDecoder().decode(encoded)
            val end = (4 until bytes.size - 3).first { bytes[it] == 0.toByte() && bytes[it + 1] == 0.toByte() && bytes[it + 2] == 1.toByte() }
            val info = AvcSpsParser.parseSps(bytes, 3, end)
            assertEquals(expected.first, info.width)
            assertEquals(expected.second, info.height)
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

    private fun sps(chroma: Int, interlaced: Boolean, separate: Boolean): ByteArray {
        val bits = StringBuilder()
        fun bit(value: Boolean) { bits.append(if (value) '1' else '0') }
        fun fixed(value: Int, count: Int) { bits.append(value.toString(2).padStart(count, '0')) }
        fun ue(value: Int) { val code = (value + 1).toString(2); bits.append("0".repeat(code.length - 1)); bits.append(code) }
        fixed(100, 8); fixed(0, 8); fixed(30, 8); ue(0); ue(chroma)
        if (chroma == 3) bit(separate)
        ue(0); ue(0); bit(false); bit(false); ue(0); ue(0); ue(0); ue(1); bit(false)
        ue(9); ue(4); bit(!interlaced); if (interlaced) bit(false)
        bit(true); bit(true); repeat(4) { ue(1) }; bit(false); bit(true)
        while (bits.length % 8 != 0) bit(false)
        return byteArrayOf(0x67) + bits.toString().chunked(8).map { it.toInt(2).toByte() }.toByteArray()
    }
}
