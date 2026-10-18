package com.wuming.tv.core.runtime.guardspider

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class SpiderInvocationSupportTest {

    @Test
    fun playbackUrlExtractsPrimitiveUrl() {
        val root = JsonParser.parseString("""{"url":"https://example.com/video.mp4"}""").asJsonObject
        assertEquals("https://example.com/video.mp4", SpiderInvocationSupport.playbackUrl(root))
    }

    @Test
    fun playbackUrlExtractsFirstHttpFromArray() {
        val root = JsonParser.parseString(
            """{"url":["ftp://skip","https://example.com/first.mp4","https://example.com/second.mp4"]}"""
        ).asJsonObject
        assertEquals("https://example.com/first.mp4", SpiderInvocationSupport.playbackUrl(root))
    }

    @Test
    fun playbackUrlExtractsFromObjectWithPosition() {
        val root = JsonParser.parseString(
            """{"url":{"values":[{"v":"https://a.com/0.mp4"},{"v":"https://a.com/1.mp4"}],"position":1}}"""
        ).asJsonObject
        assertEquals("https://a.com/1.mp4", SpiderInvocationSupport.playbackUrl(root))
    }

    @Test
    fun playbackUrlFallsBackToFirstHttpWhenPositionOutOfRange() {
        val root = JsonParser.parseString(
            """{"url":{"values":[{"v":"https://a.com/0.mp4"},{"v":"https://a.com/1.mp4"}],"position":5}}"""
        ).asJsonObject
        assertEquals("https://a.com/0.mp4", SpiderInvocationSupport.playbackUrl(root))
    }

    @Test
    fun playbackUrlPrependsPlayUrlPrefix() {
        val root = JsonParser.parseString(
            """{"playUrl":"https://proxy.com/","url":"stream.m3u8"}"""
        ).asJsonObject
        assertEquals("https://proxy.com/stream.m3u8", SpiderInvocationSupport.playbackUrl(root))
    }

    @Test
    fun playbackUrlReturnsEmptyWhenNoHttpUrl() {
        val root = JsonParser.parseString("""{"url":"ftp://no-http"}""").asJsonObject
        assertEquals("", SpiderInvocationSupport.playbackUrl(root))
    }

    @Test
    fun playbackUrlReturnsEmptyWhenUrlMissing() {
        val root = JsonObject()
        assertEquals("", SpiderInvocationSupport.playbackUrl(root))
    }

    @Test
    fun optionUrlExtractsVField() {
        val element = JsonParser.parseString("""{"v":"https://example.com/stream.mp4"}""")
        assertEquals("https://example.com/stream.mp4", SpiderInvocationSupport.optionUrl(element))
    }

    @Test
    fun optionUrlReturnsEmptyForNonObject() {
        val element = JsonParser.parseString("\"plain-string\"")
        assertEquals("", SpiderInvocationSupport.optionUrl(element))
    }

    @Test
    fun optionUrlReturnsEmptyWhenVMissing() {
        val element = JsonParser.parseString("""{"other":"value"}""")
        assertEquals("", SpiderInvocationSupport.optionUrl(element))
    }

    @Test
    fun isHttpRecognizesHttpAndHttps() {
        assertTrue(SpiderInvocationSupport.isHttp("http://example.com"))
        assertTrue(SpiderInvocationSupport.isHttp("https://example.com"))
        assertFalse(SpiderInvocationSupport.isHttp("ftp://example.com"))
        assertFalse(SpiderInvocationSupport.isHttp(null))
        assertFalse(SpiderInvocationSupport.isHttp(""))
    }

    @Test
    fun cloudPlayerFieldMapsQuark() {
        assertEquals("yq", SpiderInvocationSupport.cloudPlayerField("pan.quark.cn/s/abc"))
        assertEquals("yq", SpiderInvocationSupport.cloudPlayerField("夸克网盘"))
        assertEquals("yq", SpiderInvocationSupport.cloudPlayerField("夸父原画"))
    }

    @Test
    fun cloudPlayerFieldMapsUc() {
        assertEquals("N", SpiderInvocationSupport.cloudPlayerField("drive.uc.cn/s/abc"))
        assertEquals("N", SpiderInvocationSupport.cloudPlayerField("优汐无限"))
        assertEquals("N", SpiderInvocationSupport.cloudPlayerField("uc原画"))
        assertEquals("N", SpiderInvocationSupport.cloudPlayerField("uc智"))
    }

    @Test
    fun cloudPlayerFieldMapsAli() {
        assertEquals("SN", SpiderInvocationSupport.cloudPlayerField("alipan.com/s/abc"))
        assertEquals("SN", SpiderInvocationSupport.cloudPlayerField("aliyundrive.com/s/abc"))
        assertEquals("SN", SpiderInvocationSupport.cloudPlayerField("阿里云盘"))
        assertEquals("SN", SpiderInvocationSupport.cloudPlayerField("阿狸"))
    }

    @Test
    fun cloudPlayerFieldReturnsNullForUnknown() {
        assertNull(SpiderInvocationSupport.cloudPlayerField("example.com"))
        assertNull(SpiderInvocationSupport.cloudPlayerField(""))
    }

    @Test
    fun rootCauseReturnsDirectThrowable() {
        val error = RuntimeException("direct")
        assertSame(error, SpiderInvocationSupport.rootCause(error))
    }

    @Test
    fun rootCauseUnwrapsInvocationTargetException() {
        val target = IllegalStateException("target")
        val wrapper = InvocationTargetException(target)
        assertSame(target, SpiderInvocationSupport.rootCause(wrapper))
    }

    @Test
    fun rootCauseTraversesNestedCauses() {
        val root = IllegalArgumentException("root")
        val mid = RuntimeException("mid", root)
        val top = Exception("top", mid)
        assertSame(root, SpiderInvocationSupport.rootCause(top))
    }

    @Test
    fun valueHandlesNullAny() {
        assertEquals("", SpiderInvocationSupport.value(null as Any?))
        assertEquals("42", SpiderInvocationSupport.value(42 as Any?))
    }

    @Test
    fun valueHandlesNullString() {
        assertEquals("", SpiderInvocationSupport.value(null as String?))
        assertEquals("hello", SpiderInvocationSupport.value("hello"))
    }

    @Test
    fun valueWithFallbackUsesFallbackForNullOrBlank() {
        assertEquals("default", SpiderInvocationSupport.value(null, "default"))
        assertEquals("default", SpiderInvocationSupport.value("", "default"))
        assertEquals("default", SpiderInvocationSupport.value("  ", "default"))
        assertEquals("actual", SpiderInvocationSupport.value("actual", "default"))
    }

    @Test
    fun attachPlaybackCookiesAddsCookieHeader() {
        val result = """{"url":"https://example.com/video.mp4","header":{"Referer":"https://example.com"}}"""
        val withCookies = SpiderInvocationSupport.attachPlaybackCookies(result) { "session=abc" }
        val root = JsonParser.parseString(withCookies).asJsonObject
        assertEquals("session=abc", root.getAsJsonObject("header").get("Cookie").asString)
        assertEquals("https://example.com", root.getAsJsonObject("header").get("Referer").asString)
    }

    @Test
    fun attachPlaybackCookiesCreatesHeaderWhenMissing() {
        val result = """{"url":"https://example.com/video.mp4"}"""
        val withCookies = SpiderInvocationSupport.attachPlaybackCookies(result) { "session=abc" }
        val root = JsonParser.parseString(withCookies).asJsonObject
        assertEquals("session=abc", root.getAsJsonObject("header").get("Cookie").asString)
    }

    @Test
    fun attachPlaybackCookiesHandlesEncodedHeaderString() {
        val result = """{"url":"https://example.com/video.mp4","header":"{\"Referer\":\"https://example.com\"}"}"""
        val withCookies = SpiderInvocationSupport.attachPlaybackCookies(result) { "session=abc" }
        val root = JsonParser.parseString(withCookies).asJsonObject
        val headerStr = root.get("header").asString
        val headerObj = JsonParser.parseString(headerStr).asJsonObject
        assertEquals("session=abc", headerObj.get("Cookie").asString)
        assertEquals("https://example.com", headerObj.get("Referer").asString)
    }

    @Test
    fun attachPlaybackCookiesReturnsOriginalWhenNoUrl() {
        val result = """{"header":{"Referer":"https://example.com"}}"""
        val withCookies = SpiderInvocationSupport.attachPlaybackCookies(result) { "session=abc" }
        assertEquals(result, withCookies)
    }

    @Test
    fun attachPlaybackCookiesReturnsOriginalWhenEmptyCookie() {
        val result = """{"url":"https://example.com/video.mp4","header":{"Referer":"https://example.com"}}"""
        val withCookies = SpiderInvocationSupport.attachPlaybackCookies(result) { "" }
        assertEquals(result, withCookies)
    }

    @Test
    fun attachPlaybackCookiesReturnsOriginalOnInvalidJson() {
        val result = "not json"
        val withCookies = SpiderInvocationSupport.attachPlaybackCookies(result) { "session=abc" }
        assertEquals(result, withCookies)
    }

    @Test
    fun findFieldLocatesDeclaredField() {
        val field = SpiderInvocationSupport.findField(TestSpider::class.java, "siteKey")
        assertNotNull(field)
        assertEquals("siteKey", field!!.name)
    }

    @Test
    fun findFieldReturnsNullForMissingField() {
        val field = SpiderInvocationSupport.findField(TestSpider::class.java, "nonexistent")
        assertNull(field)
    }

    @Test
    fun findFieldLocatesInheritedField() {
        val field = SpiderInvocationSupport.findField(ChildSpider::class.java, "siteKey")
        assertNotNull(field)
    }

    @Test
    fun findLocatesExistingMethod() {
        val spider = TestSpider("key")
        val method = SpiderInvocationSupport.find(spider, "getSiteKey")
        assertNotNull(method)
    }

    @Test
    fun findReturnsNullForMissingMethod() {
        val spider = TestSpider("key")
        val method = SpiderInvocationSupport.find(spider, "nonexistent")
        assertNull(method)
    }

    @Test
    fun requiredReturnsMethodWhenFound() {
        val spider = TestSpider("key")
        val method = SpiderInvocationSupport.required(spider, "getSiteKey")
        assertNotNull(method)
    }

    @Test
    fun requiredThrowsWhenMethodMissing() {
        val spider = TestSpider("key")
        assertThrows(IllegalArgumentException::class.java) {
            SpiderInvocationSupport.required(spider, "nonexistent")
        }
    }

    @Test
    fun setSiteKeyUpdatesField() {
        val spider = TestSpider("old")
        SpiderInvocationSupport.setSiteKey(spider, "new")
        assertEquals("new", spider.siteKey)
    }

    @Test
    fun setSiteKeySilentlyIgnoresMissingField() {
        val spider = object {}
        SpiderInvocationSupport.setSiteKey(spider, "key")
    }

    @Test
    fun findCloudPlayerReturnsFieldValue() {
        val spider = TestSpider("key")
        spider.yq = "quark-player"
        val player = SpiderInvocationSupport.findCloudPlayer(spider, "夸克", null)
        assertEquals("quark-player", player)
    }

    @Test
    fun findCloudPlayerReturnsNullWhenFieldMissing() {
        val spider = object {}
        val player = SpiderInvocationSupport.findCloudPlayer(spider, "夸克", null)
        assertNull(player)
    }

    @Test
    fun findCloudPlayerReturnsNullForUnknownProvider() {
        val spider = TestSpider("key")
        val player = SpiderInvocationSupport.findCloudPlayer(spider, "unknown", null)
        assertNull(player)
    }

    private fun assertSame(expected: Any, actual: Any) {
        assertTrue("Expected <$expected> to be the same instance as <$actual>", expected === actual)
    }

    // Match Java providers' public field contract, rather than a private Kotlin
    // backing field exposed only through accessors.
    open class TestSpider(@JvmField var siteKey: String = "") {
        fun getSiteKey(): String = siteKey
        var yq: String? = null
    }

    private class ChildSpider : TestSpider("inherited")
}
