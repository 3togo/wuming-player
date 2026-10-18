package com.wuming.tv.core.epg

import java.io.ByteArrayInputStream
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class XmlTvParserTest {

    @Test
    fun parsesChannelsAliasesProgrammesAndOffsets() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tv>
              <channel id="cctv1"><display-name>CCTV-1</display-name><display-name>央视一套</display-name></channel>
              <programme channel="cctv1" start="20260810120000 +0800" stop="20260810123000 +0800">
                <title>Midday News</title><desc>Headlines</desc><category>News</category>
              </programme>
            </tv>
        """.trimIndent()

        val guide = XmlTvParser.parse(ByteArrayInputStream(xml.toByteArray()), ZoneId.of("UTC"))

        assertEquals("CCTV-1", guide.channels.single().displayName)
        assertEquals(setOf("央视一套"), guide.channels.single().aliases)
        assertEquals("Midday News", guide.programmes.single().title)
        assertEquals("News", guide.programmes.single().category)
        assertEquals(1_786_334_400_000L, guide.programmes.single().startTimeMs)
    }

    @Test
    fun ignoresMalformedProgrammeTimes() {
        val xml = "<tv><programme channel=\"demo\" start=\"bad\" stop=\"bad\"><title>Bad</title></programme></tv>"

        val guide = XmlTvParser.parse(ByteArrayInputStream(xml.toByteArray()))

        assertEquals(emptyList<ProgrammeGuideEntry>(), guide.programmes)
    }
}
