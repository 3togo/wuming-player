package com.github.catvod.bean

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DohTest {

    @Test
    fun `objectFrom valid json returns doh`() {
        val doh = Doh.objectFrom("{\"name\":\"Cloudflare\",\"url\":\"https://1.1.1.1/dns-query\"}")
        assertEquals("Cloudflare", doh.getName())
        assertEquals("https://1.1.1.1/dns-query", doh.getUrl())
    }

    @Test
    fun `objectFrom null returns new doh`() {
        val doh = Doh.objectFrom(null)
        assertEquals("", doh.getName())
        assertEquals("", doh.getUrl())
    }

    @Test
    fun `objectFrom invalid json throws exception`() {
        try {
            Doh.objectFrom("not json")
            org.junit.Assert.fail("Expected JsonSyntaxException")
        } catch (e: Exception) {
        }
    }

    @Test
    fun `name chain returns self`() {
        val doh = Doh()
        val result = doh.name("Test")
        assertTrue(result === doh)
        assertEquals("Test", doh.getName())
    }

    @Test
    fun `url chain returns self`() {
        val doh = Doh()
        val result = doh.url("https://example.com")
        assertTrue(result === doh)
        assertEquals("https://example.com", doh.getUrl())
    }

    @Test
    fun `getName returns empty for null name`() {
        val doh = Doh()
        assertEquals("", doh.getName())
    }

    @Test
    fun `getUrl returns empty for null url`() {
        val doh = Doh()
        assertEquals("", doh.getUrl())
    }

    @Test
    fun `getIps returns empty list for null ips`() {
        val doh = Doh()
        assertTrue(doh.getIps().isEmpty())
    }

    @Test
    fun `getIps returns ips when set`() {
        val doh = Doh.objectFrom("{\"ips\":[\"1.1.1.1\",\"8.8.8.8\"]}")
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), doh.getIps())
    }

    @Test
    fun `arrayFrom valid array returns list`() {
        val json = JsonParser.parseString("[{\"name\":\"A\",\"url\":\"https://a.com\"},{\"name\":\"B\",\"url\":\"https://b.com\"}]")
        val items = Doh.arrayFrom(json)
        assertEquals(2, items.size)
        assertEquals("A", items[0].getName())
        assertEquals("B", items[1].getName())
    }

    @Test
    fun `arrayFrom null returns empty list`() {
        val json = JsonParser.parseString("null")
        val items = Doh.arrayFrom(json)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `arrayFrom invalid returns empty list`() {
        val json = JsonParser.parseString("\"not an array\"")
        val items = Doh.arrayFrom(json)
        assertTrue(items.isEmpty())
    }

    @Test
    fun `equals returns true for same url`() {
        val doh1 = Doh().url("https://example.com")
        val doh2 = Doh().url("https://example.com")
        assertEquals(doh1, doh2)
    }

    @Test
    fun `equals returns false for different url`() {
        val doh1 = Doh().url("https://a.com")
        val doh2 = Doh().url("https://b.com")
        assertFalse(doh1 == doh2)
    }

    @Test
    fun `equals returns true for self`() {
        val doh = Doh()
        assertEquals(doh, doh)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val doh1 = Doh().url("https://example.com")
        val doh2 = Doh().url("https://example.com")
        assertEquals(doh1.hashCode(), doh2.hashCode())
    }

    @Test
    fun `hashCode for null url is zero`() {
        val doh = Doh()
        assertEquals(0, doh.hashCode())
    }

    @Test
    fun `toString returns json string`() {
        val doh = Doh().name("Test").url("https://test.com")
        val str = doh.toString()
        assertNotNull(str)
        assertTrue(str.contains("Test"))
        assertTrue(str.contains("https://test.com"))
    }

    @Test
    fun `getHosts returns null for no ips`() {
        val doh = Doh()
        assertNull(doh.getHosts())
    }

    @Test
    fun `getHosts returns addresses for valid ips`() {
        val doh = Doh.objectFrom("{\"ips\":[\"127.0.0.1\"]}")
        val hosts = doh.getHosts()
        assertNotNull(hosts)
        assertEquals(1, hosts!!.size)
        assertEquals("127.0.0.1", hosts[0].hostAddress)
    }
}
