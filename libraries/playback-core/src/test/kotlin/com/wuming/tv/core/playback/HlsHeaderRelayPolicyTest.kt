package com.wuming.tv.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsHeaderRelayPolicyTest {

    @Test
    fun requiresRelayWhenExtraHeadersPresent() {
        assertTrue(HlsHeaderRelayPolicy.requiresHeaderRelay(mapOf(
            "User-Agent" to "Mozilla",
            "Cookie" to "session=abc",
        )))
        assertTrue(HlsHeaderRelayPolicy.requiresHeaderRelay(mapOf(
            "Referer" to "https://example.com",
            "Authorization" to "Bearer token",
        )))
    }

    @Test
    fun doesNotRequireRelayForOnlyUserAgentAndReferer() {
        assertFalse(HlsHeaderRelayPolicy.requiresHeaderRelay(mapOf(
            "User-Agent" to "Mozilla",
            "Referer" to "https://example.com",
        )))
        assertFalse(HlsHeaderRelayPolicy.requiresHeaderRelay(mapOf(
            "user-agent" to "Mozilla",
            "referer" to "https://example.com",
        )))
    }

    @Test
    fun doesNotRequireRelayForEmptyHeaders() {
        assertFalse(HlsHeaderRelayPolicy.requiresHeaderRelay(emptyMap()))
    }
}
