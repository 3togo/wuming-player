package com.wuming.tv.core.epg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class XmlTvStreamReaderTest {
    private fun read(xml: String, visitor: XmlTvStreamReader.Visitor, limits: XmlTvStreamReader.Limits) {
        XmlTvStreamReader.read(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), visitor, limits)
    }

    @Test fun selectsBeforeCollectingTitlesAndPreservesUnicodeAndCdata() {
        val programmes = mutableListOf<XmlTvStreamReader.Programme>()
        val channels = mutableListOf<XmlTvStreamReader.Channel>()
        read("<tv><programme channel='skip'><title>" + "x".repeat(1000) + "</title></programme>"
                + "<programme channel='yes' start='s' stop='e'><title>新闻 &amp; <![CDATA[天气]]></title></programme>"
                + "<channel id='yes'><display-name>别名</display-name><icon src='logo'/></channel></tv>",
            object : XmlTvStreamReader.Visitor {
                override fun readChannels(): Boolean = true
                override fun channel(channel: XmlTvStreamReader.Channel) { channels.add(channel) }
                override fun selectProgramme(id: String): Boolean = id == "yes"
                override fun programme(programme: XmlTvStreamReader.Programme) { programmes.add(programme) }
            }, XmlTvStreamReader.Limits(10000, 10, 32, 10))
        assertEquals(listOf(XmlTvStreamReader.Programme("yes", "s", "e", "新闻 & 天气")), programmes)
        assertEquals(listOf("别名"), channels[0].names)
        assertEquals("logo", channels[0].icon)
    }

    @Test fun allowsExternalDtdButRejectsInternalDtdAndExternalEntities() {
        val visitor = object : XmlTvStreamReader.Visitor {
            override fun readChannels(): Boolean = true
        }
        read("<!DOCTYPE tv SYSTEM 'xmltv.dtd'><tv><channel id='c'><display-name>ch</display-name></channel></tv>",
            visitor, XmlTvStreamReader.DEFAULT_LIMITS)
        assertThrows(Exception::class.java) { read("<!DOCTYPE tv [<!ENTITY x 'expanded'>]><tv>&x;</tv>",
            object : XmlTvStreamReader.Visitor {}, XmlTvStreamReader.DEFAULT_LIMITS) }
        assertThrows(Exception::class.java) { read(
            "<!DOCTYPE tv SYSTEM 'xmltv.dtd' [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]>" +
            "<tv>&xxe;</tv>",
            object : XmlTvStreamReader.Visitor {}, XmlTvStreamReader.DEFAULT_LIMITS) }
    }

    @Test fun limitsBytesRecordsDepthAndRetainedText() {
        val all = object : XmlTvStreamReader.Visitor {
            override fun selectProgramme(id: String): Boolean = true
        }
        assertThrows(Exception::class.java) { read("<tv>    </tv>", all, XmlTvStreamReader.Limits(5, 10, 10, 10)) }
        assertThrows(Exception::class.java) { read("<tv><programme/><programme/></tv>", all, XmlTvStreamReader.Limits(100, 1, 10, 10)) }
        assertThrows(Exception::class.java) { read("<tv><a><b/></a></tv>", all, XmlTvStreamReader.Limits(100, 10, 10, 2)) }
        assertThrows(Exception::class.java) { read("<tv><programme><title>12345</title></programme></tv>", all,
            XmlTvStreamReader.Limits(100, 10, 4, 10)) }
    }

    @Test fun rejectsMalformedAndWrongRoot() {
        assertThrows(Exception::class.java) { read("<tv><programme></tv>", object : XmlTvStreamReader.Visitor {}, XmlTvStreamReader.DEFAULT_LIMITS) }
        assertThrows(Exception::class.java) { read("<other/>", object : XmlTvStreamReader.Visitor {}, XmlTvStreamReader.DEFAULT_LIMITS) }
    }

    @Test fun cancellationPreservesInterruptAndCallerOwnsStream() {
        val closed = booleanArrayOf(false)
        val input = object : ByteArrayInputStream("<tv/>".toByteArray(StandardCharsets.UTF_8)) {
            override fun close() { closed[0] = true }
        }
        Thread.currentThread().interrupt()
        try {
            assertThrows(Exception::class.java) {
                XmlTvStreamReader.read(input, object : XmlTvStreamReader.Visitor {}, XmlTvStreamReader.DEFAULT_LIMITS)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        assertFalse(closed[0])
    }
}
