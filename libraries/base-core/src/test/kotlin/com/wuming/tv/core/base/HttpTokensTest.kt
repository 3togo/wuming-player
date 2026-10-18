package com.wuming.tv.core.base

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTokensTest {

    @Test
    fun headerNamesAreRfc7230Tokens() {
        assertTrue(HttpTokens.isToken("Content-Type"))
        assertTrue(HttpTokens.isToken("X-OpenDrive-Token"))
        assertTrue(HttpTokens.isToken("user_agent"))
        assertTrue(HttpTokens.isToken("a"))
    }

    @Test
    fun separatorsAndOversizedNamesAreRejected() {
        assertFalse(HttpTokens.isToken("Content-Type: application/json"))
        assertFalse(HttpTokens.isToken("bad name"))
        assertFalse(HttpTokens.isToken("bad\nname"))
        assertFalse(HttpTokens.isToken(""))
        assertFalse(HttpTokens.isToken("a".repeat(129)))
        assertFalse(HttpTokens.isToken(null))
    }

    @Test
    fun headerValuesMustBePresentBoundedAndPrintable() {
        assertTrue(HttpTokens.isHeaderValue("bytes=0-"))
        assertTrue(HttpTokens.isHeaderValue("a".repeat(8192)))
        assertFalse(HttpTokens.isHeaderValue(""))
        assertFalse(HttpTokens.isHeaderValue(null))
        assertFalse(HttpTokens.isHeaderValue("a".repeat(8193)))
        assertFalse(HttpTokens.isHeaderValue("value\r\nX-Injected: 1"))
        assertFalse(HttpTokens.isHeaderValue("a".repeat(10), 5))
        assertTrue(HttpTokens.isHeaderValue("a".repeat(5), 5))
    }
}
