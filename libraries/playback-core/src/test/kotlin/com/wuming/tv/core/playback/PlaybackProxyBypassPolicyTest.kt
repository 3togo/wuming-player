package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackProxyBypassPolicyTest {
    @Test fun preservesProxyAndBothExistingExclusionLists() {
        val original = mapOf("HTTPS_PROXY" to "http://127.0.0.1:1234", "no_proxy" to "localhost, .example.test", "NO_PROXY" to "127.0.0.1", "OTHER" to "value")
        val result = PlaybackProxyBypassPolicy.playerEnvironment(original)
        assertEquals("localhost,.example.test,127.0.0.1,video.cqwqq.com", result["no_proxy"])
        assertEquals(result["no_proxy"], result["NO_PROXY"])
        assertEquals(original["HTTPS_PROXY"], result["HTTPS_PROXY"])
        assertEquals("value", result["OTHER"])
        assertEquals("localhost, .example.test", original["no_proxy"])
        assertFalse(result["no_proxy"]!!.split(',').contains("cqwqq.com"))
    }

    @Test fun isIdempotentAndSupportsLowercaseProxy() {
        val once = PlaybackProxyBypassPolicy.playerEnvironment(mapOf("http_proxy" to "http://proxy.test", "no_proxy" to "video.cqwqq.com"))
        assertEquals(once, PlaybackProxyBypassPolicy.playerEnvironment(once))
        assertEquals("video.cqwqq.com", once["no_proxy"])
    }

    @Test fun leavesDirectEnvironmentUnchanged() {
        val original = mapOf("NO_PROXY" to "localhost", "https_proxy" to "")
        assertSame(original, PlaybackProxyBypassPolicy.playerEnvironment(original))
    }
}
