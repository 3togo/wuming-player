package com.github.catvod.utils

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun `parse valid json returns element`() {
        val result = Json.parse("{\"key\":\"value\"}")
        assertTrue(result.isJsonObject)
        assertEquals("value", result.asJsonObject.get("key").asString)
    }

    @Test
    fun `parse invalid json returns empty object`() {
        val result = Json.parse("not json")
        assertTrue(result.isJsonObject)
        assertTrue(result.asJsonObject.entrySet().isEmpty())
    }

    @Test
    fun `parse null returns empty object`() {
        val result = Json.parse(null as String?)
        assertTrue(result.isJsonObject)
    }

    @Test
    fun `isObj returns true for object string`() {
        assertTrue(Json.isObj("{\"a\":1}"))
    }

    @Test
    fun `isObj returns false for array string`() {
        assertFalse(Json.isObj("[1,2]"))
    }

    @Test
    fun `isObj returns false for null`() {
        assertFalse(Json.isObj(null))
    }

    @Test
    fun `isObj returns false for empty string`() {
        assertFalse(Json.isObj(""))
    }

    @Test
    fun `isArray returns true for array string`() {
        assertTrue(Json.isArray("[1,2]"))
    }

    @Test
    fun `isArray returns false for object string`() {
        assertFalse(Json.isArray("{\"a\":1}"))
    }

    @Test
    fun `isArray returns false for null`() {
        assertFalse(Json.isArray(null))
    }

    @Test
    fun `isEmpty returns true for missing key`() {
        val obj = JsonObject()
        assertTrue(Json.isEmpty(obj, "missing"))
    }

    @Test
    fun `isEmpty returns true for null value`() {
        val obj = JsonObject()
        obj.add("key", null)
        assertTrue(Json.isEmpty(obj, "key"))
    }

    @Test
    fun `isEmpty returns true for empty array`() {
        val obj = JsonObject()
        obj.add("key", com.google.gson.JsonArray())
        assertTrue(Json.isEmpty(obj, "key"))
    }

    @Test
    fun `isEmpty returns true for blank string`() {
        val obj = JsonObject()
        obj.addProperty("key", "   ")
        assertTrue(Json.isEmpty(obj, "key"))
    }

    @Test
    fun `isEmpty returns false for non-empty array`() {
        val obj = JsonObject()
        val arr = com.google.gson.JsonArray()
        arr.add("item")
        obj.add("key", arr)
        assertFalse(Json.isEmpty(obj, "key"))
    }

    @Test
    fun `safeString returns value for valid key`() {
        val obj = JsonObject()
        obj.addProperty("key", "value")
        assertEquals("value", Json.safeString(obj, "key"))
    }

    @Test
    fun `safeString returns empty for missing key`() {
        val obj = JsonObject()
        assertEquals("", Json.safeString(obj, "missing"))
    }

    @Test
    fun `safeString trims whitespace`() {
        val obj = JsonObject()
        obj.addProperty("key", "  value  ")
        assertEquals("value", Json.safeString(obj, "key"))
    }

    @Test
    fun `safeListString returns list for array value`() {
        val obj = JsonObject()
        val arr = com.google.gson.JsonArray()
        arr.add("a")
        arr.add("b")
        obj.add("key", arr)
        assertEquals(listOf("a", "b"), Json.safeListString(obj, "key"))
    }

    @Test
    fun `safeListString returns empty string for json object value`() {
        val obj = JsonObject()
        val inner = JsonObject()
        inner.addProperty("subkey", "value")
        obj.add("key", inner)
        val result = Json.safeListString(obj, "key")
        assertEquals(1, result.size)
        assertEquals("", result[0])
    }

    @Test
    fun `safeListString returns empty for missing key`() {
        val obj = JsonObject()
        assertTrue(Json.safeListString(obj, "missing").isEmpty())
    }

    @Test
    fun `safeListElement returns list for array value`() {
        val obj = JsonObject()
        val arr = com.google.gson.JsonArray()
        arr.add(JsonObject())
        obj.add("key", arr)
        assertEquals(1, Json.safeListElement(obj, "key").size)
    }

    @Test
    fun `safeListElement returns empty for missing key`() {
        val obj = JsonObject()
        assertTrue(Json.safeListElement(obj, "missing").isEmpty())
    }

    @Test
    fun `safeObject returns object for valid element`() {
        val obj = JsonObject()
        obj.addProperty("key", "value")
        val result = Json.safeObject(obj)
        assertEquals("value", result.get("key").asString)
    }

    @Test
    fun `safeObject parses primitive string to object`() {
        val primitive = JsonPrimitive("{\"key\":\"value\"}")
        val result = Json.safeObject(primitive)
        assertEquals("value", result.get("key").asString)
    }

    @Test
    fun `safeObject returns empty for invalid`() {
        val primitive = JsonPrimitive("not json")
        val result = Json.safeObject(primitive)
        assertTrue(result.entrySet().isEmpty())
    }

    @Test
    fun `toMap from string returns map`() {
        val map = Json.toMap("{\"a\":\"1\",\"b\":\"2\"}")
        assertNotNull(map)
        assertEquals("1", map!!["a"])
        assertEquals("2", map["b"])
    }

    @Test
    fun `toMap from null returns null`() {
        assertNull(Json.toMap(null as String?))
    }

    @Test
    fun `toMap from empty string returns null`() {
        assertNull(Json.toMap(""))
    }

    @Test
    fun `toMap from element returns map`() {
        val obj = JsonObject()
        obj.addProperty("key", "value")
        val map = Json.toMap(obj)
        assertEquals("value", map["key"])
    }

    @Test
    fun `GSON is not null`() {
        assertNotNull(Json.GSON)
    }
}
