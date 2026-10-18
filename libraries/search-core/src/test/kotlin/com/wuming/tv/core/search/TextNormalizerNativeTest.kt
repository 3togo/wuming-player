package com.wuming.tv.core.search

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerNativeTest {

    @Test
    fun `normalize null returns empty`() {
        assertEquals("", TextNormalizerNative.normalize(null))
    }

    @Test
    fun `normalize trims and lowercases in fallback`() {
        assertEquals("hello world", TextNormalizerNative.normalize("  Hello World  "))
    }

    @Test
    fun `normalize handles empty string`() {
        assertEquals("", TextNormalizerNative.normalize(""))
        assertEquals("", TextNormalizerNative.normalize("   "))
    }

    @Test
    fun `normalize handles cjk text`() {
        assertEquals("庆余年", TextNormalizerNative.normalize("庆余年"))
        assertEquals("庆余年 第二季", TextNormalizerNative.normalize("  庆余年 第二季  "))
    }

    @Test
    fun `normalize handles mixed content`() {
        assertEquals("1080p 蓝光", TextNormalizerNative.normalize("1080P 蓝光"))
    }
}
