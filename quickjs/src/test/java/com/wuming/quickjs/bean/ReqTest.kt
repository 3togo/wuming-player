package com.wuming.quickjs.bean

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReqTest {

    @Test
    fun `objectFrom valid json with all fields`() {
        val req = Req.objectFrom("{\"buffer\":1,\"redirect\":0,\"timeout\":5000,\"postType\":\"form\",\"method\":\"post\",\"body\":\"data\",\"headers\":{\"Content-Type\":\"text/plain\"}}")
        assertEquals(1, req.getBuffer())
        assertEquals(0, req.getRedirect())
        assertEquals(5000, req.getTimeout())
        assertEquals("form", req.getPostType())
        assertEquals("post", req.getMethod())
        assertEquals("data", req.getBody())
        val header = req.getHeader()
        assertNotNull(header)
        assertEquals("text/plain", header!!["Content-Type"])
    }

    @Test
    fun `objectFrom with defaults`() {
        val req = Req.objectFrom("{}")
        assertEquals(0, req.getBuffer())
        assertEquals(1, req.getRedirect())
        assertEquals(10000, req.getTimeout())
        assertEquals("json", req.getPostType())
        assertEquals("get", req.getMethod())
    }

    @Test
    fun `isRedirect returns true when redirect is 1`() {
        val req = Req.objectFrom("{\"redirect\":1}")
        assertEquals(true, req.isRedirect())
    }

    @Test
    fun `isRedirect returns false when redirect is 0`() {
        val req = Req.objectFrom("{\"redirect\":0}")
        assertEquals(false, req.isRedirect())
    }

    @Test
    fun `getCharset returns charset from Content-Type`() {
        val req = Req.objectFrom("{\"headers\":{\"Content-Type\":\"text/html; charset=GBK\"}}")
        assertEquals("GBK", req.getCharset())
    }

    @Test
    fun `getCharset returns charset from lowercase content-type`() {
        val req = Req.objectFrom("{\"headers\":{\"content-type\":\"text/html; charset=ISO-8859-1\"}}")
        assertEquals("ISO-8859-1", req.getCharset())
    }

    @Test
    fun `getCharset returns UTF-8 when no Content-Type`() {
        val req = Req.objectFrom("{}")
        assertEquals("UTF-8", req.getCharset())
    }

    @Test
    fun `getCharset returns UTF-8 when no charset in Content-Type`() {
        val req = Req.objectFrom("{\"headers\":{\"Content-Type\":\"text/html\"}}")
        assertEquals("UTF-8", req.getCharset())
    }

    @Test
    fun `getHeader returns null when no headers`() {
        val req = Req.objectFrom("{}")
        assertNull(req.getHeader())
    }

    @Test
    fun `getBody returns null when not set`() {
        val req = Req.objectFrom("{}")
        assertNull(req.getBody())
    }

    @Test
    fun `getData returns null when not set`() {
        val req = Req.objectFrom("{}")
        assertNull(req.getData())
    }
}
