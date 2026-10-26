package com.wuming.tv.core.base

import org.junit.Assert.*
import org.junit.Test

class ProviderHttpHeadersTest {
    @Test fun removesOnlyNonAsciiUserAgentDecoration() {
        assertEquals("TVBox Desktop", ProviderHttpHeaders.value("user-agent", "১১TVBox Desktopौौ"))
        assertEquals("TVBox-Desktop/0.1", ProviderHttpHeaders.value("User-Agent", "১১ौ"))
        assertEquals("Browser/1.0", ProviderHttpHeaders.value("User-Agent", "Browser/1.0"))
        assertEquals("秘密", ProviderHttpHeaders.value("Authorization", "秘密"))
        assertThrows(IllegalArgumentException::class.java) {
            ProviderHttpHeaders.value("User-Agent", "字\r\nX-Injected: yes")
        }
    }
}
