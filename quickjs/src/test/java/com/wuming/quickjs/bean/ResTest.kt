package com.wuming.quickjs.bean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ResTest {

    @Test
    fun `objectFrom valid json with all fields`() {
        val res = Res.objectFrom("{\"code\":200,\"buffer\":1,\"content\":\"hello\",\"headers\":{\"Content-Type\":\"text/html\"}}")
        assertEquals(200, res.getCode())
        assertEquals(1, res.getBuffer())
        assertEquals("hello", res.getContent())
        val header = res.getHeader()
        assertNotNull(header)
        assertEquals("text/html", header!!["Content-Type"])
    }

    @Test
    fun `objectFrom with default code`() {
        val res = Res.objectFrom("{\"content\":\"test\"}")
        assertEquals(200, res.getCode())
    }

    @Test
    fun `objectFrom with default buffer`() {
        val res = Res.objectFrom("{\"content\":\"test\"}")
        assertEquals(0, res.getBuffer())
    }

    @Test
    fun `objectFrom with empty content`() {
        val res = Res.objectFrom("{\"code\":404}")
        assertEquals("", res.getContent())
    }

    @Test
    fun `getContentType returns content-type from header`() {
        val res = Res.objectFrom("{\"headers\":{\"Content-Type\":\"application/json\"}}")
        assertEquals("application/json", res.getContentType())
    }

    @Test
    fun `getContentType returns content-type lowercase from header`() {
        val res = Res.objectFrom("{\"headers\":{\"content-type\":\"application/xml\"}}")
        assertEquals("application/xml", res.getContentType())
    }

    @Test
    fun `getContentType returns default when no header`() {
        val res = Res.objectFrom("{\"content\":\"test\"}")
        assertEquals("application/octet-stream", res.getContentType())
    }

    @Test
    fun `getContentType returns default when null headers`() {
        val res = Res.objectFrom("{}")
        assertEquals("application/octet-stream", res.getContentType())
    }

    @Test
    fun `getHeader returns null when no headers`() {
        val res = Res.objectFrom("{\"content\":\"test\"}")
        assertNull(res.getHeader())
    }

    @Test
    fun `getStream returns content bytes`() {
        val res = Res.objectFrom("{\"content\":\"hello\",\"buffer\":0}")
        val stream = res.getStream()
        val bytes = stream.readBytes()
        assertEquals("hello", String(bytes))
    }
}
