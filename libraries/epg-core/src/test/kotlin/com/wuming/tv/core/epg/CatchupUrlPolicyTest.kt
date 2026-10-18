package com.wuming.tv.core.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class CatchupUrlPolicyTest {

    private val UTC = ZoneId.of("UTC")

    @Test
    fun expandsProgrammeWindowUsingExplicitZoneAndLocale() {
        val template = CatchupTemplate("append",
            "?playseek=\${(b)yyyyMMddHHmmss}-\${(e)yyyyMMddHHmmss}",
            "/PLTV/", "/PLTV/,/TVOD/")
        val start = Instant.parse("2026-08-10T01:02:03Z").toEpochMilli()
        val end = Instant.parse("2026-08-10T02:03:04Z").toEpochMilli()

        assertEquals("https://live/TVOD/channel?playseek=20260810010203-20260810020304",
            CatchupUrlPolicy.format(template, "https://live/PLTV/channel", start, end,
                UTC, Locale.ROOT))
    }

    @Test
    fun changesQuestionMarkToAmpersandWhenBaseAlreadyHasAQuery() {
        val template = CatchupTemplate("append", "?start={utc:}", "", "")

        assertEquals("https://live/channel?token=x&start=1",
            CatchupUrlPolicy.format(template, "https://live/channel?token=x", 1000, 2000,
                UTC, Locale.ROOT))
    }

    @Test
    fun defaultTemplateReturnsExpandedSourceWithoutBaseUrl() {
        val template = CatchupTemplate("default", "https://archive/{utc:}", "", "")

        assertEquals("https://archive/1", CatchupUrlPolicy.format(template, "ignored", 1000, 2000,
            UTC, Locale.ROOT))
    }

    @Test
    fun matchingSupportsLiteralAndRegularExpressionPatterns() {
        assertTrue(CatchupUrlPolicy.matches(CatchupTemplate("", "", "/PLTV/", ""),
            "https://live/PLTV/channel"))
        assertTrue(CatchupUrlPolicy.matches(CatchupTemplate("", "", "channel\\d+", ""),
            "https://live/channel12"))
        assertFalse(CatchupUrlPolicy.matches(CatchupTemplate(), "https://live/channel"))
    }
}
