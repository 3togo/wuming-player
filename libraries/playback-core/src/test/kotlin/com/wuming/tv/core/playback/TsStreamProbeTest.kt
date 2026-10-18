package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TsStreamProbeTest {

    private val spsParser = object : TsStreamProbe.SpsParserPort {
        override fun parseSps(data: ByteArray, offset: Int, end: Int): HlsResolutionAdFilter.VideoInfo =
            HlsResolutionAdFilter.VideoInfo(1280, 720, 0.0)
    }

    @Test
    fun readReturnsNullForNullInput() {
        assertNull(TsStreamProbe.create(spsParser).read(null))
    }

    @Test
    fun readReturnsNullForTooShortInput() {
        assertNull(TsStreamProbe.create(spsParser).read(ByteArray(20)))
    }

    @Test
    fun readReturnsNullForInputExceedingMaxBytes() {
        assertNull(TsStreamProbe.create(spsParser).read(ByteArray(TsStreamProbe.MAX_BYTES + 1)))
    }

    @Test
    fun readReturnsNullForNonTsData() {
        assertNull(TsStreamProbe.create(spsParser).read("not a ts stream".toByteArray()))
    }
}
