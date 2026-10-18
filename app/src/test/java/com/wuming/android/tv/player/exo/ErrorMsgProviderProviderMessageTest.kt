package com.wuming.android.tv.player.exo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-safe coverage for [ErrorMsgProvider.providerMessage]. This static helper is pure and does
 * not depend on Android resources or media3, unlike [ErrorMsgProvider.get] (which builds a
 * [androidx.media3.common.PlaybackException] and resolves R.string via [com.wuming.android.tv.utils.ResUtil],
 * so it is exercised by instrumentation instead).
 */
class ErrorMsgProviderProviderMessageTest {

    @Test
    fun returnsValueForMatchingHeader() {
        val headers = mapOf("X-OpenDrive-Error" to listOf("rate limited"))
        assertEquals("rate limited", ErrorMsgProvider.providerMessage(headers))
    }

    @Test
    fun isCaseInsensitive() {
        val headers = mapOf("x-opendrive-error" to listOf("case-ok"))
        assertEquals("case-ok", ErrorMsgProvider.providerMessage(headers))
    }

    @Test
    fun nullHeadersReturnsEmpty() {
        assertEquals("", ErrorMsgProvider.providerMessage(null))
    }

    @Test
    fun blankValueReturnsEmpty() {
        assertEquals("", ErrorMsgProvider.providerMessage(mapOf("X-OpenDrive-Error" to listOf("   "))))
    }

    @Test
    fun tooLongValueReturnsEmpty() {
        assertEquals("", ErrorMsgProvider.providerMessage(mapOf("X-OpenDrive-Error" to listOf("x".repeat(257)))))
    }

    @Test
    fun valueWithCrReturnsEmpty() {
        assertEquals("", ErrorMsgProvider.providerMessage(mapOf("X-OpenDrive-Error" to listOf("a\rb"))))
    }

    @Test
    fun valueWithLfReturnsEmpty() {
        assertEquals("", ErrorMsgProvider.providerMessage(mapOf("X-OpenDrive-Error" to listOf("a\nb"))))
    }

    @Test
    fun missingHeaderReturnsEmpty() {
        assertEquals("", ErrorMsgProvider.providerMessage(mapOf("X-Other" to listOf("value"))))
    }

    @Test
    fun nullValueReturnsEmpty() {
        @Suppress("UNCHECKED_CAST")
        val headers = mapOf("X-OpenDrive-Error" to listOf<String?>(null)) as Map<String, List<String>>
        assertEquals("", ErrorMsgProvider.providerMessage(headers))
    }

    @Test
    fun returnsFirstWhenMultiple() {
        val headers = mapOf("X-OpenDrive-Error" to listOf("first", "second"))
        assertEquals("first", ErrorMsgProvider.providerMessage(headers))
    }
}
