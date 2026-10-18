package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class VodDisplayNameTest {

    @Test
    fun `preserves plain title`() {
        assertEquals("庆余年", VodDisplayName.format("庆余年"))
        assertEquals("流浪地球 2", VodDisplayName.format("流浪地球 2"))
    }

    @Test
    fun `strips tech tokens and region from brackets`() {
        assertEquals("夜王", VodDisplayName.format("[HD1080P][蓝光原盘][动作片] 夜王(导演版) Night King"))
        assertEquals("哈利波特与死亡圣器下",
            VodDisplayName.format("【4K】【喜剧】哈利波特与死亡圣器下(2011) 2160p BDRip【字幕组】"))
    }

    @Test
    fun `preserves edition words like extended cut`() {
        assertEquals("阿凡达 加长版", VodDisplayName.format("[4K HDR] 阿凡达 加长版(2009) BluRay REMUX -GroupX"))
    }

    @Test
    fun `strips inline tech tokens with word boundaries`() {
        assertEquals("哈利波特与死亡圣器下",
            VodDisplayName.format("哈利波特与死亡圣器下 Harry Potter and the Deathly Hallows Part 2 2011 2160p BluRay x265 AAC"))
    }

    @Test
    fun `returns original when result is empty`() {
        assertEquals("4K", VodDisplayName.format("4K"))
    }

    @Test
    fun `handles null input safely`() {
        assertEquals("", VodDisplayName.format(null))
    }

    @Test
    fun `handles blank input`() {
        assertEquals("", VodDisplayName.format(""))
        assertEquals("", VodDisplayName.format("   "))
    }

    @Test
    fun `strips year from title`() {
        assertEquals("流浪地球", VodDisplayName.format("流浪地球 2019"))
        assertEquals("流浪地球", VodDisplayName.format("流浪地球2019"))
    }

    @Test
    fun `strips multiple bracket groups`() {
        assertEquals("测试影片", VodDisplayName.format("[4K][HDR][动作片][中国大陆] 测试影片(2023)"))
    }

    @Test
    fun `preserves title with trailing latin after cjk`() {
        assertEquals("测试影片", VodDisplayName.format("测试影片 ABC"))
    }

    @Test
    fun `strips size prefix`() {
        assertEquals("影片名", VodDisplayName.format("[2GB] 影片名"))
    }
}
