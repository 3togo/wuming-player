package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiderLocationTest {

    @Test
    fun splitsTheMd5SuffixHostsAppend() {
        val reference = SpiderLocation.parse(
            "https://raw.githubusercontent.com/jifeng250/tvbox-sources/main/pg.jar;md5;" +
                "dffec63fb83a2b31b8606bd4bf6bead9"
        )
        assertEquals("https://raw.githubusercontent.com/jifeng250/tvbox-sources/main/pg.jar", reference!!.location)
        assertEquals(SpiderDigestKind.MD5, reference.digest!!.kind)
        assertEquals("dffec63fb83a2b31b8606bd4bf6bead9", reference.digest.value)
        assertFalse(reference.digest.isRemote)
    }

    @Test
    fun recognizesTheSha256Spelling() {
        val reference = SpiderLocation.parse("https://host.test/spider.jar;sha256;abc123")
        assertEquals("https://host.test/spider.jar", reference!!.location)
        assertEquals(SpiderDigestKind.SHA256, reference.digest!!.kind)
        assertEquals("abc123", reference.digest.value)
    }

    @Test
    fun keepsAMirrorPrefixInFrontOfTheLocation() {
        val reference = SpiderLocation.parse(
            "https://gh-proxy.com/https://raw.githubusercontent.com/jifeng250/tvbox-sources/main/xyq.jar;md5;" +
                "705ba6e23c4384c37a11dd904727520b"
        )
        assertEquals(
            "https://gh-proxy.com/https://raw.githubusercontent.com/jifeng250/tvbox-sources/main/xyq.jar",
            reference!!.location
        )
    }

    @Test
    fun leavesRelativeToHostedLocationsWithoutADigestAlone() {
        assertEquals("./custom_spider.jar", SpiderLocation.parse("./custom_spider.jar")!!.location)
        assertNull(SpiderLocation.parse("./custom_spider.jar")!!.digest)
        assertEquals("file:///sdcard/box/spider.jar", SpiderLocation.parse(" file:///sdcard/box/spider.jar ")!!.location)
    }

    @Test
    fun flagsADigestPublishedAsItsOwnUrl() {
        val reference = SpiderLocation.parse("https://host.test/fan.jar;md5;https://host.test/fan.jar.md5")
        assertEquals("https://host.test/fan.jar", reference!!.location)
        assertTrue(reference.digest!!.isRemote)
    }

    @Test
    fun takesTheFirstSuffixWhenAHostWritesTwo() {
        val reference = SpiderLocation.parse("https://host.test/a.jar;md5;1111;sha256;2222")
        assertEquals("https://host.test/a.jar", reference!!.location)
        assertEquals(SpiderDigestKind.MD5, reference.digest!!.kind)
        assertEquals("1111", reference.digest.value)
    }

    @Test
    fun hasNoReferenceForBlankInput() {
        assertNull(SpiderLocation.parse(null))
        assertNull(SpiderLocation.parse("   "))
        assertEquals("", SpiderLocation.locationOf(null))
    }
}
