package com.wuming.tv.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeDisplayNameTest {

    @Test
    fun `cloud filename shows trailing episode instead of repeated title`() {
        assertEquals(
            "第14集",
            EpisodeDisplayName.format(
                "[4.76MB] 113.母荣子贵，被冷面赛车手宠上天（）未删减 14.mp4",
            ),
        )
    }

    @Test
    fun `season episode marker survives filename cleanup`() {
        assertEquals("S01E08", EpisodeDisplayName.format("A very long programme title.S01E08.mkv"))
    }

    @Test
    fun `ordinary provider episode label remains unchanged`() {
        assertEquals("第8集", EpisodeDisplayName.format("第8集"))
    }

    @Test
    fun `video filename without episode marker only loses extension`() {
        assertEquals("Movie title", EpisodeDisplayName.format("Movie title.mp4"))
    }

    @Test
    fun `unplayed labels and release noise match Android`() {
        assertEquals("第01集", EpisodeDisplayName.format("01"))
        assertEquals("第2集", EpisodeDisplayName.format("[172.03MB]2.mkv"))
        assertEquals("正片", EpisodeDisplayName.format("[3.45GB]4K.mkv"))
        assertEquals("Part 2", EpisodeDisplayName.format("Part 2.mkv"))
    }

    @Test
    fun `episode grid distributes near fifth column across full viewport`() {
        assertEquals(217, EpisodeGridSizing.cellWidth(1_088, 220))
    }

    @Test
    fun `episode grid fills narrower viewport without a trailing gap`() {
        assertEquals(200, EpisodeGridSizing.cellWidth(800, 220))
    }
}
