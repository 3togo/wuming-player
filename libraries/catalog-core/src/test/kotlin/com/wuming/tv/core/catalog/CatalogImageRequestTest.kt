package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CatalogImageRequestTest {

    @Test
    fun `parses Android compatible inline poster headers`() {
        val request = CatalogImageRequestParser.parse(
            "//img.example/poster.webp@Referer=https://source.example/@Cookie=session=abc" +
                "@Headers={\"X-Image-Key\":\"key\"}",
        )

        assertEquals("https://img.example/poster.webp", request.location)
        assertEquals("https://source.example/", request.headers["Referer"])
        assertEquals("session=abc", request.headers["Cookie"])
        assertEquals("key", request.headers["X-Image-Key"])
    }

    @Test
    fun `drops malformed header metadata without dropping poster URL`() {
        val request = CatalogImageRequestParser.parse("https://img.example/poster.jpg@Headers=not-json")

        assertEquals("https://img.example/poster.jpg", request.location)
        assertFalse(request.headers.containsKey("Host"))
    }
}
