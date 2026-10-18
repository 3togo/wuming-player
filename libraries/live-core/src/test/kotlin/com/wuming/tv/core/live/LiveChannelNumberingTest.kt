package com.wuming.tv.core.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveChannelNumberingTest {

    @Test
    fun padsTheOrdinalLikeTheLiveListsDo() {
        assertEquals("001", LiveChannelNumbering.label(1))
        assertEquals("042", LiveChannelNumbering.label(42))
        assertEquals("1000", LiveChannelNumbering.label(1000))
    }
}
