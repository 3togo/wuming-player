package com.wuming.tv.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LivePlaylistParserTest {

    @Test
    fun acceptsLeadingUnicodeByteOrderMarkInText() {
        val playlist = LivePlaylistParser.parse("\uFEFF#EXTM3U\n#EXTINF:-1,Channel\nhttps://media.example/live.m3u8")
        assertEquals("Channel", playlist.groups.single().channels.single().name)
    }

    @Test
    fun parsesM3uMetadataGroupsAlternatesAndHeaders() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U x-tvg-url="https://epg.example/guide.xml"
            #EXTINF:-1 tvg-id="cctv1" tvg-name="CCTV 1" tvg-logo="https://img.example/1.png" group-title="News",CCTV-1
            #EXTVLCOPT:http-referrer=https://portal.example/
            https://cdn1.example/live.m3u8|User-Agent=TVBox%20Desktop
            https://cdn2.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals(listOf("https://epg.example/guide.xml"), playlist.epgUrls)
        assertEquals(1, playlist.channelCount)
        val channel = playlist.groups.single().channels.single()
        assertEquals("News", playlist.groups.single().name)
        assertEquals("CCTV-1", channel.name)
        assertEquals("cctv1", channel.tvgId)
        assertEquals(2, channel.streams.size)
        assertEquals("TVBox Desktop", channel.streams.first().headers["User-Agent"])
        assertEquals("https://portal.example/", channel.streams.first().headers["Referer"])
    }

    @Test
    fun parsesTvBoxTxtGroupsAndAlternateUrls() {
        val playlist = LivePlaylistParser.parse(
            """
            News,#genre#
            CCTV-1,https://one.example/live.m3u8#https://two.example/live.m3u8
            Movies,#genre#
            HBO,https://movies.example/hbo.m3u8
            """.trimIndent(),
        )

        assertEquals(listOf("News", "Movies"), playlist.groups.map { it.name })
        assertEquals(2, playlist.channelCount)
        assertEquals(2, playlist.groups.first().channels.single().streams.size)
    }

    @Test
    fun mergesAlternateDisplayNamesWithTheSameTvGuideIdentity() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV1" group-title="CCTV",CCTV-1
            https://one.example/cctv1.m3u8
            #EXTINF:-1 tvg-name="CCTV1" group-title="CCTV",CCTV1
            https://two.example/cctv1.m3u8
            """.trimIndent(),
        )

        assertEquals(1, playlist.channelCount)
        assertEquals(2, playlist.groups.single().channels.single().streams.size)
    }

    @Test
    fun rejectsEmptyAndUnplayableInput() {
        assertThrows(IllegalArgumentException::class.java) { LivePlaylistParser.parse("  ") }
        assertThrows(IllegalArgumentException::class.java) { LivePlaylistParser.parse("not a playlist") }
    }

    @Test
    fun parsesMultipleEpgUrlsFromExtm3uHeader() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U tvg-url="https://epg1.example/guide.xml,https://epg2.example/guide.xml"
            #EXTINF:-1 group-title="News",Channel
            https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals(
            listOf("https://epg1.example/guide.xml", "https://epg2.example/guide.xml"),
            playlist.epgUrls,
        )
    }

    @Test
    fun parsesTxtFormatWithDirectiveHeaders() {
        val playlist = LivePlaylistParser.parse(
            """
            News,#genre#
            ua=Mozilla/5.0
            referer=https://portal.example/
            CCTV-1,https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        val channel = playlist.groups.single().channels.single()
        assertEquals("Mozilla/5.0", channel.streams.first().headers["User-Agent"])
        assertEquals("https://portal.example/", channel.streams.first().headers["Referer"])
    }

    @Test
    fun parsesM3uWithExtvlcoptOriginHeader() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News",Channel
            #EXTVLCOPT:http-origin=https://origin.example/
            https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals("https://origin.example/",
            playlist.groups.single().channels.single().streams.first().headers["Origin"])
    }

    @Test
    fun parsesM3uWithStreamLevelHeadersInUrl() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News",Channel
            https://cdn.example/live.m3u8|User-Agent=Custom&Referer=https://ref.example/
            """.trimIndent(),
        )

        val stream = playlist.groups.single().channels.single().streams.first()
        assertEquals("Custom", stream.headers["User-Agent"])
        assertEquals("https://ref.example/", stream.headers["Referer"])
    }

    @Test
    fun unnamedChannelFallsBackToDefaultName() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News"
            https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals("Unnamed channel",
            playlist.groups.single().channels.single().name)
    }

    @Test
    fun channelWithoutGroupFallsBackToDefaultGroup() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1,Channel
            https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals("Live TV", playlist.groups.single().name)
    }

    @Test
    fun deduplicatesStreamsByDistinctUrl() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-name="CH" group-title="G",CH
            https://cdn.example/live.m3u8
            https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals(1, playlist.groups.single().channels.single().streams.size)
    }

    @Test
    fun parsesM3uWithHttpUserAgentAttribute() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News" http-user-agent="Mozilla/5.0",Channel
            https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals("Mozilla/5.0",
            playlist.groups.single().channels.single().streams.first().headers["User-Agent"])
    }

    @Test
    fun parsesM3uWithTvgChnoNumber() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-chno="5" group-title="News",Channel
            https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals("5", playlist.groups.single().channels.single().number)
    }

    @Test
    fun parsesM3uWithTvgLogo() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://img.example/logo.png" group-title="News",Channel
            https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals("https://img.example/logo.png",
            playlist.groups.single().channels.single().logo)
    }

    @Test
    fun parsesTxtWithOriginHeader() {
        val playlist = LivePlaylistParser.parse(
            """
            News,#genre#
            origin=https://origin.example/
            CCTV-1,https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals("https://origin.example/",
            playlist.groups.single().channels.single().streams.first().headers["Origin"])
    }

    @Test
    fun parsesTxtWithQuotedUserAgent() {
        val playlist = LivePlaylistParser.parse(
            """
            News,#genre#
            ua="Mozilla/5.0"
            CCTV-1,https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals("Mozilla/5.0",
            playlist.groups.single().channels.single().streams.first().headers["User-Agent"])
    }

    @Test
    fun rejectsPlaylistWithOnlyComments() {
        assertThrows(IllegalArgumentException::class.java) {
            LivePlaylistParser.parse("# only comments\n// more comments")
        }
    }

    @Test
    fun parsesM3uWithMultipleGroups() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News",CCTV-1
            https://cdn.example/news.m3u8
            #EXTINF:-1 group-title="Sports",CCTV-5
            https://cdn.example/sports.m3u8
            """.trimIndent(),
        )

        assertEquals(listOf("News", "Sports"), playlist.groups.map { it.name })
    }

    @Test
    fun parsesTxtWithCommentLines() {
        val playlist = LivePlaylistParser.parse(
            """
            // This is a comment
            News,#genre#
            CCTV-1,https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals(1, playlist.channelCount)
        assertEquals("News", playlist.groups.single().name)
    }

    @Test
    fun parsesM3uWithExtvlcoptUserAgent() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="News",Channel
            #EXTVLCOPT:http-user-agent=VLC/3.0
            https://cdn.example/live.m3u8
            """.trimIndent(),
        )

        assertEquals("VLC/3.0",
            playlist.groups.single().channels.single().streams.first().headers["User-Agent"])
    }

    @Test
    fun channelCountSumsAcrossAllGroups() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="A",CH1
            https://cdn.example/1.m3u8
            #EXTINF:-1 group-title="B",CH2
            https://cdn.example/2.m3u8
            #EXTINF:-1 group-title="B",CH3
            https://cdn.example/3.m3u8
            """.trimIndent(),
        )

        assertEquals(3, playlist.channelCount)
        assertEquals(2, playlist.groups.size)
    }

    @Test
    fun numbersChannelsWithoutADeclaredPositionInDocumentOrder() {
        val playlist = LivePlaylistParser.parse(
            """
            央视频道,#genre#
            CCTV-1,https://one.example/cctv1.m3u8
            CCTV-2,https://two.example/cctv2.m3u8
            卫视频道,#genre#
            云南卫视,https://three.example/yunnan.m3u8
            """.trimIndent(),
        )

        assertEquals(
            listOf("001", "002"),
            playlist.groups.first().channels.map { it.number },
        )
        assertEquals("003", playlist.groups.last().channels.single().number)
    }

    @Test
    fun aDeclaredNumberLeavesTheOrdinalUntouched() {
        val playlist = LivePlaylistParser.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-chno="5" group-title="News",CH5
            https://cdn.example/5.m3u8
            #EXTINF:-1 group-title="News",CH6
            https://cdn.example/6.m3u8
            """.trimIndent(),
        )

        assertEquals(listOf("5", "001"), playlist.groups.single().channels.map { it.number })
    }

    @Test
    fun treatsAuaQueryParameterAsPartOfTheUrlNotAsADirective() {
        val playlist = LivePlaylistParser.parse(
            """
            卫视频道,#genre#
            云南卫视,http://nn.example.cn/xzc2.php?ua=TW96aWxsYS81LjA=&hls=aHR0cDov
            厦门卫视,http://e2.example.cn:28888/rtp/239.61.2.11:8060#http://backup.example.cn/x.m3u8?key=tv&playlive=1
            """.trimIndent(),
        )

        val channels = playlist.groups.single().channels
        assertEquals("http://nn.example.cn/xzc2.php?ua=TW96aWxsYS81LjA=&hls=aHR0cDov", channels.first().streams.single().url)
        assertEquals(2, channels.last().streams.size)
    }
}
