package com.wuming.tv.core.cloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class Pan123ProviderTest {
    private class Memory : CloudHandleVault.Store {
        val values = mutableMapOf<String, String>()
        override fun reference(provider: String, handle: String) = "vault:opendrive/$provider/$handle"
        override fun put(reference: String, json: String) { values[reference] = json }
        override fun get(reference: String) = values[reference] ?: ""
        override fun remove(reference: String) { values.remove(reference) }
        override fun clearProvider(provider: String) { values.keys.removeIf { it.startsWith("vault:opendrive/$provider/") } }
    }
    private class Port : Pan123Provider.Port {
        var credential = "a".repeat(64)
        var cleared = 0
        var calls = 0
        var headers = emptyMap<String, String>()
        var body = JsonObject()
        var response = """{"data":{"DownloadURL":"https://cdn.example/video.mp4"}}"""
        override fun token() = credential
        override fun clearCredentials() { cleared++; credential = "" }
        override fun get(url: String, query: Map<String, String>, headers: Map<String, String>): JsonObject { calls++; return JsonParser.parseString(response).asJsonObject }
        override fun post(url: String, body: JsonObject, headers: Map<String, String>): JsonObject {
            calls++; this.body = body; this.headers = headers
            return JsonParser.parseString(response).asJsonObject
        }
        override fun authExpired() { }
        override fun invalidUrl(itemId: String) { }
        override fun userAgent() = "fixture"
    }
    private fun imported(provider: Pan123Provider) = provider.prepareExternal(
        OpenDriveExternalPayload.create("pan123原画", "shareKey1++123456++s3-key/value++2048++etag-value")!!
    )!!.materialize()
    @Test fun externalMetadataStaysLazyAndShareDownloadUsesReviewedHeaders() {
        val port = Port()
        val provider = Pan123Provider(port, CloudHandleVault(Memory())) { it.endsWith(".mp4") }
        val handle = imported(provider)
        assertEquals(0, port.calls)
        assertFalse(handle.contains("shareKey1"))
        val playback = provider.resolve(handle)
        assertEquals("https://cdn.example/video.mp4", playback.url)
        assertEquals("android", port.headers["platform"])
        assertEquals("Bearer " + port.credential, port.headers["Authorization"])
        assertEquals("shareKey1", port.body["ShareKey"].asString)
        assertEquals(mapOf("User-Agent" to "fixture"), playback.headers)
    }
    @Test fun persistedHandleSurvivesMemoryResetAndLogoutRemovesOnlyProviderRecords() {
        val memory = Memory()
        val port = Port()
        val provider = Pan123Provider(port, CloudHandleVault(memory)) { true }
        val handle = imported(provider)
        assertTrue(provider.persistHandle(handle))
        provider.credentialsRestored()
        assertEquals("https://cdn.example/video.mp4", provider.resolve(handle).url)
        val restarted = Pan123Provider(port, CloudHandleVault(memory)) { true }
        assertEquals("https://cdn.example/video.mp4", restarted.resolve(handle).url)
        memory.values["vault:opendrive/other/keep"] = "untouched"
        provider.logout()
        assertEquals(1, port.cleared)
        assertEquals(mapOf("vault:opendrive/other/keep" to "untouched"), memory.values)
        assertThrows(IllegalArgumentException::class.java) { provider.resolve(handle) }
    }
    @Test fun readsSavedFileMetadataFromBeforeTheCoreExtraction() {
        val memory = Memory()
        val handle = "legacy-handle"
        memory.values["vault:opendrive/123pan/$handle"] = """
            {"fileId":"123456","name":"Saved movie.mp4","s3KeyFlag":"s3-key/value",
             "size":2048,"etag":"etag-value","type":0,"shareKey":"shareKey1","sharePassword":""}
        """.trimIndent()
        val port = Port()
        val provider = Pan123Provider(port, CloudHandleVault(memory)) { true }
        assertEquals("https://cdn.example/video.mp4", provider.resolve(handle).url)
        assertEquals("shareKey1", port.body["ShareKey"].asString)
        assertEquals("123456", port.body["FileID"].asString)
        assertEquals("s3-key/value", port.body["S3KeyFlag"].asString)
        assertEquals(2048L, port.body["Size"].asLong)
        assertEquals("etag-value", port.body["Etag"].asString)
        assertTrue(provider.persistHandle(handle))
        assertEquals(setOf("fileId", "name", "s3KeyFlag", "size", "etag", "type", "shareKey", "sharePassword"),
            JsonParser.parseString(memory.values.getValue("vault:opendrive/123pan/$handle")).asJsonObject.keySet())
    }
    @Test fun invalidAuthenticationNeverTriggersNetworkOrCredentialDeletionAndWrappedUrlsDecode() {
        val port = Port()
        val provider = Pan123Provider(port, CloudHandleVault(Memory())) { true }
        val handle = imported(provider)
        port.credential = ""
        assertThrows(IllegalStateException::class.java) { provider.resolve(handle) }
        assertEquals(0, port.calls)
        assertEquals(0, port.cleared)
        port.credential = "b".repeat(64)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("https://cdn.example/wrapped.mp4".toByteArray())
        port.response = """{"data":{"DownloadURL":"https://wrapper.example/file?params=$encoded"}}"""
        assertEquals("https://cdn.example/wrapped.mp4", provider.resolve(handle).url)
    }
    @Test fun canonicalShareGrammarRejectsLookalikeHostsAndRetainsPasscode() {
        assertEquals("https://www.123pan.com/s/Abcdef12?pwd=9xYz", Pan123Provider.canonicalShareUrl("https://123912.com/s/Abcdef12?pwd=9xYz"))
        assertNull(Pan123Provider.canonicalShareUrl("https://www.123pan.com.evil.example/s/Abcdef12"))
        assertNull(Pan123Provider.canonicalShareUrl("http://www.123pan.com/s/Abcdef12"))
    }
}
