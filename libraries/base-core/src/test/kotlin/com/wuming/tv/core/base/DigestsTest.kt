package com.wuming.tv.core.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class DigestsTest {

    @Test
    fun knownSha256VectorIsLowercaseHex() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Digests.sha256Hex("")
        )
        assertEquals(
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            Digests.sha256Hex("test")
        )
    }

    @Test
    fun textAndBytesAgreeOnUtf8() {
        val value = "無名-credentials"
        assertEquals(Digests.sha256Hex(value), Digests.sha256Hex(value.toByteArray(StandardCharsets.UTF_8)))
        assertNotEquals(Digests.sha256Hex(value), Digests.sha256Hex("wuming-credentials"))
    }

    @Test
    fun streamedDigestMatchesTheBufferedOne() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        assertEquals(Digests.sha256Hex(bytes), Digests.sha256Hex(ByteArrayInputStream(bytes)))
    }

    @Test
    fun tagsArePrefixesOfTheFullDigest() {
        val value = "https://example.test/playlist.m3u8"
        assertEquals(16, Digests.sha256Tag(value).length)
        assertTrue(Digests.sha256Hex(value).startsWith(Digests.sha256Tag(value)))
        assertEquals(Digests.sha256Hex(value), Digests.sha256Tag(value, 64))
    }

    @Test
    fun onlyFullLowercaseDigestsValidate() {
        assertTrue(Digests.isSha256Hex("a".repeat(64)))
        assertTrue(Digests.isSha256Hex(Digests.sha256Hex("value")))
        assertFalse(Digests.isSha256Hex("A".repeat(64)))
        assertFalse(Digests.isSha256Hex("a".repeat(63)))
        assertFalse(Digests.isSha256Hex(null))
    }
}
