package com.wuming.tv.desktop.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

class DesktopDepotTest {

    @Test
    fun `loads the same bundled VOD and Live depot used by leanback`() {
        val depot = DesktopDepot.load()

        if (!com.wuming.tv.core.base.EditionSettings.current.nativeProtocols) {
            assertTrue(depot.vod.isEmpty())
            assertTrue(depot.live.any { it.location == "assets://live/public_samples.m3u8" })
        }
        assertTrue(depot.live.isNotEmpty())
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle")) }
        val androidAsset = JsonParser.parseString(Files.readString(
            root.resolve("app/src/main/assets/depot.json"))).asJsonObject
        fun urls(key: String) = androidAsset.getAsJsonArray(key)
            .map { it.asJsonObject.get("url").asString.trim() }.filter(String::isNotEmpty).toSet()
        assertEquals(urls("vod"), depot.vod.map { it.location }.toSet())
        assertEquals(urls("live"), depot.live.map { it.location }.toSet())
        assertEquals(depot.vod.size, depot.vod.map { it.location }.distinct().size)
        assertEquals(depot.live.size, depot.live.map { it.location }.distinct().size)
    }

    @Test
    fun `recognizes explicit and named warehouses`() {
        val depot = DesktopDepot.load {
            """{"vod":[{"name":"Marked","url":"https://example.org/a.json","warehouse":true},
                {"name":"影视多仓","url":"https://example.org/b.json"}],"live":[]}"""
                .byteInputStream()
        }
        assertTrue(depot.vod.all { it.warehouse })
    }
}
