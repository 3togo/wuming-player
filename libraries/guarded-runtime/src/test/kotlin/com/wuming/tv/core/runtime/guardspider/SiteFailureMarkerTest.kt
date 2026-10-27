package com.wuming.tv.core.runtime.guardspider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SiteFailureMarkerTest {
    @Test
    fun classifiesAntiBotPages() {
        assertEquals(
            SiteFailureMarker.BROWSER_REASON,
            SiteFailureMarker.classify(listOf("403 Forbidden: browser verification required")),
        )
    }

    @Test
    fun classifiesParserMismatch() {
        assertEquals(
            SiteFailureMarker.PARSER_REASON,
            SiteFailureMarker.classify(
                listOf(
                    "sourceNameBD5播放",
                    "org.json.JSONException: No value for froms",
                    "at com.github.catvod.spider.Libvio.detailContent(Unknown Source)",
                ),
            ),
        )
    }

    @Test
    fun classifiesTimeouts() {
        assertEquals(
            SiteFailureMarker.NETWORK_REASON,
            SiteFailureMarker.classify(listOf("java.net.SocketTimeoutException: timeout")),
        )
    }

    @Test
    fun leavesUnknownCausesUnclassified() {
        assertNull(SiteFailureMarker.classify(emptyList()))
        assertNull(SiteFailureMarker.classify(listOf("provider returned nothing")))
    }

    @Test
    fun prefersAntiBotOverParserSignal() {
        assertEquals(
            SiteFailureMarker.BROWSER_REASON,
            SiteFailureMarker.classify(
                listOf("org.json.JSONException: No value for froms", "403 Forbidden: browser verification required"),
            ),
        )
    }
}
