package com.wuming.tv.core.cloud

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudJsonTest {

    @Test
    fun stringReturnsEmptyForMissingKey() {
        val obj = JsonObject()
        assertEquals("", CloudJson.string(obj, "missing"))
        assertEquals("", CloudJson.string(null, "missing"))
    }

    @Test
    fun stringReturnsTrimmedValue() {
        val obj = JsonObject()
        obj.addProperty("key", "  value  ")
        assertEquals("value", CloudJson.string(obj, "key"))
    }

    @Test
    fun numberReturnsZeroForMissingKey() {
        val obj = JsonObject()
        assertEquals(0L, CloudJson.number(obj, "missing"))
        assertEquals(0L, CloudJson.number(null, "missing"))
    }

    @Test
    fun numberReturnsValue() {
        val obj = JsonObject()
        obj.addProperty("key", 42L)
        assertEquals(42L, CloudJson.number(obj, "key"))
    }

    @Test
    fun boolReturnsFalseForMissingKey() {
        val obj = JsonObject()
        assertFalse(CloudJson.bool(obj, "missing"))
        assertFalse(CloudJson.bool(null, "missing"))
    }

    @Test
    fun boolReturnsValue() {
        val obj = JsonObject()
        obj.addProperty("key", true)
        assertTrue(CloudJson.bool(obj, "key"))
    }

    @Test
    fun objectReturnsEmptyForMissingKey() {
        val obj = JsonObject()
        assertEquals(0, CloudJson.jsonObject(obj, "missing").size())
        assertEquals(0, CloudJson.jsonObject(null, "missing").size())
    }

    @Test
    fun objectReturnsNestedObject() {
        val parent = JsonObject()
        val child = JsonObject()
        child.addProperty("inner", "value")
        parent.add("child", child)
        assertEquals("value", CloudJson.string(CloudJson.jsonObject(parent, "child"), "inner"))
    }

    @Test
    fun validDownloadUrlAcceptsHttps() {
        assertTrue(CloudJson.validDownloadUrl("https://example.com/file.mp4"))
    }

    @Test
    fun validDownloadUrlRejectsHttp() {
        assertFalse(CloudJson.validDownloadUrl("http://example.com/file.mp4"))
    }

    @Test
    fun validDownloadUrlRejectsUserInfo() {
        assertFalse(CloudJson.validDownloadUrl("https://user:pass@example.com/file.mp4"))
    }

    @Test
    fun validDownloadUrlRejectsNull() {
        assertFalse(CloudJson.validDownloadUrl(null))
    }

    @Test
    fun validDownloadUrlRejectsInvalidUri() {
        assertFalse(CloudJson.validDownloadUrl("not a url"))
    }
}
