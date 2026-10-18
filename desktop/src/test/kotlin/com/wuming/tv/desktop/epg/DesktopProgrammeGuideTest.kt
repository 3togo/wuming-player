package com.wuming.tv.desktop.epg

import com.wuming.tv.core.epg.GuideChannel
import com.wuming.tv.core.epg.ProgrammeGuideEntry
import com.wuming.tv.core.epg.XmlTvGuide
import com.wuming.tv.core.live.LiveChannel
import com.wuming.tv.core.live.LiveStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopProgrammeGuideTest {

    @Test
    fun resolvesStableIdBeforeNameFallbackAndFindsNowNext() {
        val entries = listOf(
            ProgrammeGuideEntry("cctv1", "Now", 1_000, 2_000),
            ProgrammeGuideEntry("cctv1", "Next", 2_000, 3_000),
        )
        val guide = DesktopProgrammeGuide(
            XmlTvGuide(listOf(GuideChannel("cctv1", "CCTV-1 HD", setOf("央视一套"))), entries),
        )

        val exact = guide.nowNext(channel("Wrong name", "cctv1"), 1_500)
        val fallback = guide.nowNext(channel("CCTV 1"), 1_500)

        assertEquals("Now", exact.now?.title)
        assertEquals("Next", exact.next?.title)
        assertEquals("Now", fallback.now?.title)
    }

    @Test
    fun loadsGzipXmlTvFromLocalFile() {
        val file = Files.createTempFile("tvbox-epg-", ".xml.gz")
        val xml = """
            <tv>
              <channel id="demo"><display-name>Demo</display-name></channel>
              <programme channel="demo" start="20260810120000 +0000" stop="20260810130000 +0000">
                <title>Demo programme</title>
              </programme>
            </tv>
        """.trimIndent().toByteArray()
        try {
            GZIPOutputStream(Files.newOutputStream(file)).use { it.write(xml) }

            val guide = DesktopProgrammeGuideLoader().load(file.toString())

            assertEquals(1, guide.programmeCount)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun retriesForbiddenAptvGuideWithCompatibleUserAgent() {
        val attempts = mutableListOf<Map<String, String>>()
        val xml = """
            <tv>
              <channel id="demo"><display-name>Demo</display-name></channel>
              <programme channel="demo" start="20260810120000 +0000" stop="20260810130000 +0000">
                <title>Demo programme</title>
              </programme>
            </tv>
        """.trimIndent().toByteArray()
        val loader = DesktopProgrammeGuideLoader { _, headers ->
            attempts += headers
            if (headers.isEmpty()) throw IllegalArgumentException("Resource server returned HTTP 403.")
            xml
        }

        assertEquals(1, loader.load("https://epg.aptv.app/xml").programmeCount)
        assertEquals(2, attempts.size)
        assertTrue(attempts.last()["User-Agent"].orEmpty().startsWith("AptvPlayer/"))
    }

    private fun channel(name: String, tvgId: String = "") = LiveChannel(
        name = name,
        tvgId = tvgId,
        streams = listOf(LiveStream("https://media.example/live.m3u8")),
    )
}
