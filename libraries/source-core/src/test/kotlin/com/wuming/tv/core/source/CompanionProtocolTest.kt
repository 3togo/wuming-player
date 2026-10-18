package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionProtocolTest {

    @Test
    fun `endpoint constants match the documented paths`() {
        assertEquals("/pair", CompanionProtocol.PAIR)
        assertEquals("/desktop/v1/sources", CompanionProtocol.SOURCES)
        assertEquals("/desktop/v1/search", CompanionProtocol.SEARCH)
        assertEquals("/desktop/v1/detail", CompanionProtocol.DETAIL)
        assertEquals("/desktop/v1/play", CompanionProtocol.PLAY)
    }

    @Test
    fun `source validation preserves the server's configured key space`() {
        CompanionProtocol.validateSource("catvod")
        CompanionProtocol.validateSource("csp_豆瓣-1")
        CompanionProtocol.validateSource("provider key")
        assertThrows(IllegalArgumentException::class.java) { CompanionProtocol.validateSource("") }
        assertThrows(IllegalArgumentException::class.java) {
            CompanionProtocol.validateSource("x".repeat(CompanionProtocol.MAX_PARAMETER_LENGTH + 1))
        }
    }

    @Test
    fun `required parameters are trimmed and reject blanks controls and oversized values`() {
        assertEquals("value", CompanionProtocol.requireParameter("  value  ", "id"))
        CompanionProtocol.validateQuery("anything up to here")
        assertThrows(IllegalArgumentException::class.java) { CompanionProtocol.validateQuery("  ") }
        assertThrows(IllegalArgumentException::class.java) { CompanionProtocol.validateQuery("bad\nquery") }
        assertThrows(IllegalArgumentException::class.java) {
            CompanionProtocol.validateQuery("x".repeat(CompanionProtocol.MAX_PARAMETER_LENGTH + 1))
        }
    }

    @Test
    fun `token pattern is a 256-bit hex string`() {
        assertTrue("a".repeat(64).matches(Regex(CompanionProtocol.TOKEN_PATTERN)))
        assertFalse("z".repeat(64).matches(Regex(CompanionProtocol.TOKEN_PATTERN)))
        assertFalse("a".repeat(63).matches(Regex(CompanionProtocol.TOKEN_PATTERN)))
    }
}
