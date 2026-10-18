package com.wuming.android.tv.bean;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VodDisplayNameTest {

    @Test
    public void ordinaryNamePreservesTitle() {
        assertEquals("庆余年", VodDisplayName.format("庆余年"));
        assertEquals("流浪地球 2", VodDisplayName.format("流浪地球 2"));
    }

    @Test
    public void bracketResolutionAndQualityStripped() {
        assertEquals("夜王",
                VodDisplayName.format("[HD1080P][蓝光原盘][动作片] 夜王(导演版) Night King"));
        assertEquals("哈利波特与死亡圣器下",
                VodDisplayName.format("【4K】【喜剧】哈利波特与死亡圣器下(2011) 2160p BDRip【字幕组】"));
    }

    @Test
    public void regionAndGenreStripped() {
        assertEquals("德 赛",
                VodDisplayName.format("[土耳其][剧情] 德.赛（2026）The Odyssey"));
    }

    @Test
    public void standaloneYearAndCodecTagsRemoved() {
        assertEquals("哈利波特与死亡圣器下",
                VodDisplayName.format("哈利波特与死亡圣器下 Harry Potter and the Deathly Hallows Part 2 2011 2160p BluRay x265 AAC"));
    }

    @Test
    public void editionWordsPreserved() {
        assertEquals("阿凡达 加长版", VodDisplayName.format("[4K HDR] 阿凡达 加长版(2009) BluRay REMUX -GroupX"));
        assertEquals("哈利波特与密室 导演剪辑版",
                VodDisplayName.format("【4K修复典藏版】哈利波特与密室 导演剪辑版 [杜比视界][国配]"));
    }
}
