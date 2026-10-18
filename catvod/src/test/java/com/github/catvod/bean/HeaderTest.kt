package com.github.catvod.bean

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderTest {

    @Test
    fun `arrayFrom valid array returns list`() {
        val json = JsonParser.parseString("[{\"host\":\"example.com\",\"header\":{\"key\":\"value\"}}]")
        val headers = Header.arrayFrom(json)
        assertEquals(1, headers.size)
        assertEquals("example.com", headers[0].getHost())
    }

    @Test
    fun `arrayFrom empty array returns empty list`() {
        val json = JsonParser.parseString("[]")
        val headers = Header.arrayFrom(json)
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `arrayFrom null element returns empty list`() {
        val json = JsonParser.parseString("null")
        val headers = Header.arrayFrom(json)
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `arrayFrom invalid json returns empty list`() {
        val json = JsonParser.parseString("\"not an array\"")
        val headers = Header.arrayFrom(json)
        assertTrue(headers.isEmpty())
    }

    @Test
    fun `getHost returns empty for null host`() {
        val json = JsonParser.parseString("[{\"header\":{}}]")
        val headers = Header.arrayFrom(json)
        assertEquals("", headers[0].getHost())
    }

    @Test
    fun `getHeader returns json element`() {
        val json = JsonParser.parseString("[{\"host\":\"example.com\",\"header\":{\"key\":\"value\"}}]")
        val headers = Header.arrayFrom(json)
        val header = headers[0].getHeader()
        assertNotNull(header)
        assertTrue(header!!.isJsonObject)
        assertEquals("value", header.asJsonObject.get("key").asString)
    }

    @Test
    fun `getHeader returns null when not set`() {
        val json = JsonParser.parseString("[{\"host\":\"example.com\"}]")
        val headers = Header.arrayFrom(json)
        assertNull(headers[0].getHeader())
    }

    @Test
    fun `arrayFrom multiple items`() {
        val json = JsonParser.parseString("[{\"host\":\"a.com\"},{\"host\":\"b.com\"}]")
        val headers = Header.arrayFrom(json)
        assertEquals(2, headers.size)
        assertEquals("a.com", headers[0].getHost())
        assertEquals("b.com", headers[1].getHost())
    }
}

private fun assertNotNull(value: Any?) {
    org.junit.Assert.assertNotNull(value)
}
