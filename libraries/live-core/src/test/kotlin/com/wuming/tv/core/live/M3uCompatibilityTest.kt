package com.wuming.tv.core.live

import org.junit.Assert.*
import org.junit.Test

class M3uCompatibilityTest {
    @Test fun quotesInsideUnquotedUrlsDoNotHideTheNameDelimiter() {
        for (logo in listOf("https://img.test/o'news.png", "https://img.test/logo?token='abc")) {
            val line = "#EXTINF:-1 tvg-logo=$logo tvg-name=\"Guide, HD\" group-title=News,Channel, HD"
            val playlist = LivePlaylistParser.parse("#EXTM3U\n$line\nhttps://media.test/live")
            val channel = playlist.groups.single().channels.single()
            assertEquals("Channel, HD", channel.name)
            assertEquals("Guide, HD", channel.tvgName)
            assertEquals(logo, channel.logo)
        }
    }

    @Test fun legacyNativeAvailabilityCannotBypassM3uGrammar() {
        val previous = com.wuming.tv.core.base.native.NativeLoader.available
        try {
            com.wuming.tv.core.base.native.NativeLoader.available = true
            val playlist = LivePlaylistParser.parse("#EXTM3U\n#EXTINF:-1,News, HD\nhttps://media.test/v")
            assertEquals("News, HD", playlist.groups.single().channels.single().name)
        } finally { com.wuming.tv.core.base.native.NativeLoader.available = previous }
    }

    @Test fun headerWithoutDisplayDelimiterRetainsAllUnquotedGuideUrls() {
        assertEquals(listOf("https://a.test/one.xml", "https://a.test/two.xml"),
            M3uDirectives.epgUrls("#EXTM3U tvg-url=one.xml,two.xml", "https://a.test/list.m3u"))
    }

    @Test fun relativeGuidesPreserveEncodedBracesAndLocalFileBases() {
        assertEquals("https://a.test/guide?literal=%7Bname%7D&ch={name}",
            M3uDirectives.resolve("https://a.test/config/list.m3u", "../guide?literal=%7Bname%7D&ch={name}"))
        assertEquals("file:/home/example/live%20files/guide.xml",
            M3uDirectives.resolve("/home/example/live files/list.m3u", "guide.xml"))
    }

    @Test fun preservesNamesCommasQuoteStylesAndGroupOverrides() {
        val playlist = LivePlaylistParser.parse("""
            #EXTM3U url-tvg='../guide.xml.gz,../epg?ch={name}&date={date}'
            #EXTINF:-1 tvg-id='news-1' tvg-name='新闻,高清' group-title='old',频道,高清
            #EXTGRP:新闻
            https://media.test/one
            https://media.test/backup
            #EXTINF:-1 tvg-id=other group-title=地方,另一个台
            https://media.test/two
        """.trimIndent(), "https://example.test/config/list.m3u")
        assertEquals(listOf("https://example.test/guide.xml.gz", "https://example.test/epg?ch={name}&date={date}"), playlist.epgUrls)
        assertEquals(listOf("新闻", "地方"), playlist.groups.map { it.name })
        val first = playlist.groups.first().channels.single()
        assertEquals("频道,高清", first.name)
        assertEquals("news-1", first.tvgId)
        assertEquals("新闻,高清", first.tvgName)
        assertEquals(2, first.streams.size)
        assertEquals("other", playlist.groups.last().channels.single().tvgId)
    }

    @Test fun attributesStopBeforeTheDisplayNameAndDoNotStealSubstringKeys() {
        val line = "#EXTINF:-1 custom-tvg-id='wrong' tvg-id=right group-title=News,Name tvg-id=title"
        assertEquals("right", M3uDirectives.attributes(line)["tvg-id"])
        assertEquals("Name tvg-id=title", M3uDirectives.name(line))
        assertEquals("Fallback, name", M3uDirectives.name("#EXTINF:-1 tvg-name=\"Fallback, name\""))
    }

    @Test fun duplicateNamesKeepTheirDistinctGuideIdentities() {
        val playlist = LivePlaylistParser.parse("#EXTM3U\n#EXTINF:-1 tvg-id='one',News\nhttps://media.test/1\n#EXTINF:-1 tvg-id='two',News\nhttps://media.test/2")
        assertEquals(listOf("one", "two"), playlist.groups.single().channels.map { it.tvgId })
    }
}
