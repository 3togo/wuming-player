package com.wuming.tv.core.live

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class LivePlaylistJsonCodecTest {
    @Test fun `aliases feed shared parser with ordered alternatives and metadata`() {
        val playlist = LivePlaylistParser.parse("""
            [{"group":"News","channels":[
              {"name":"台一","tvg-id":"one","tvg-name":"One","tvg-chno":7,"tvg-logo":"logo",
               "http-user-agent":"Radio","headers":{"Referer":"https://portal.example/"},
               "url":["https://a/one.m3u8","https://a/one.m3u8","https://b/one.m3u8|User-Agent=Other"]},
              {"name":"台二","url":"https://a/two.m3u8"}]}]
        """.trimIndent())
        assertEquals("News", playlist.groups.single().name)
        assertEquals(listOf("台一", "台二"), playlist.groups.single().channels.map { it.name })
        val channel = playlist.groups.single().channels.first()
        assertEquals("one", channel.tvgId)
        assertEquals("One", channel.tvgName)
        assertEquals("7", channel.number)
        assertEquals("logo", channel.logo)
        assertEquals(2, channel.streams.size)
        assertEquals("Radio", channel.streams.first().headers["User-Agent"])
        assertEquals("Other", channel.streams.last().headers["User-Agent"])
        assertEquals("https://portal.example/", channel.streams.last().headers["Referer"])
    }

    @Test fun `normalization retains canonical Android options and prefers canonical fields`() {
        val input = """[{"name":"Original","group":"Alias","pass":"secret","channel":[{
            "name":"One","urls":["https://a/live"],"url":"https://ignored/live",
            "parse":1,"click":"#play","drm":{"type":"widevine"},"catchup":{"type":"append","source":"?start={utc}"}
        }]}]"""
        val normalized = LivePlaylistJsonCodec.normalize(input)
        val group = JsonParser.parseString(normalized).asJsonArray.single().asJsonObject
        assertEquals("Original", group.get("name").asString)
        assertEquals("secret", group.get("pass").asString)
        val channel = group.getAsJsonArray("channel").single().asJsonObject
        assertEquals("https://a/live", channel.getAsJsonArray("urls").single().asString)
        assertEquals(1, channel.get("parse").asInt)
        assertEquals("#play", channel.get("click").asString)
        assertEquals("widevine", channel.getAsJsonObject("drm").get("type").asString)
        assertEquals("append", channel.getAsJsonObject("catchup").get("type").asString)
        assertEquals(normalized, LivePlaylistJsonCodec.normalize(normalized))
    }

    @Test fun `bad entries do not hide valid groups and empty playlists fail clearly`() {
        val playlist = LivePlaylistParser.parse("\uFEFF" + """[null,5,{}, {"name":"Valid","channel":[null,{}, {"name":"One","urls":[null,{},"https://a/live"]}]}]""")
        assertEquals(1, playlist.channelCount)
        assertThrows(IllegalArgumentException::class.java) { LivePlaylistParser.parse("[]") }
        assertThrows(IllegalArgumentException::class.java) { LivePlaylistJsonCodec.normalize("{}") }
    }

    @Test fun `same channel merges alternatives but keeps header differences`() {
        val playlist = LivePlaylistParser.parse("""[{"name":"Radio","channel":[
            {"name":"One","urls":["https://a/live"],"header":"{\"User-Agent\":\"One\"}"},
            {"name":"One","urls":["https://a/live"],"ua":"Two"}
        ]}]""")
        val streams = playlist.groups.single().channels.single().streams
        assertEquals(listOf("One", "Two"), streams.map { it.headers["User-Agent"] })
    }
}
