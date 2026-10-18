package com.github.catvod.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TransTest {

    @Test
    fun `s2t with pass true returns original text`() {
        assertEquals("hello", Trans.s2t(true, "hello"))
    }

    @Test
    fun `t2s with pass true returns original text`() {
        assertEquals("hello", Trans.t2s(true, "hello"))
    }

    @Test
    fun `s2t with pass true returns empty string`() {
        assertEquals("", Trans.s2t(true, ""))
    }

    @Test
    fun `t2s with pass true returns empty string`() {
        assertEquals("", Trans.t2s(true, ""))
    }

    @Test
    fun `s2t converts simplified to traditional`() {
        val result = Trans.s2t(false, "万与丑")
        assertEquals("萬與醜", result)
    }

    @Test
    fun `t2s converts traditional to simplified`() {
        val result = Trans.t2s(false, "萬與醜")
        assertEquals("万与丑", result)
    }

    @Test
    fun `s2t leaves unknown characters unchanged`() {
        val result = Trans.s2t(false, "abc123")
        assertEquals("abc123", result)
    }

    @Test
    fun `t2s leaves unknown characters unchanged`() {
        val result = Trans.t2s(false, "abc123")
        assertEquals("abc123", result)
    }

    @Test
    fun `s2t null returns null`() {
        assertEquals(null, Trans.s2t(null as String?))
    }

    @Test
    fun `t2s null returns null`() {
        assertEquals(null, Trans.t2s(null as String?))
    }

    @Test
    fun `s2t and t2s are inverse operations`() {
        val original = "万与丑专业丛东丝"
        val traditional = Trans.s2t(false, original)
        val backToSimplified = Trans.t2s(false, traditional)
        assertEquals(original, backToSimplified)
    }

    @Test
    fun `pass returns boolean`() {
        val result = Trans.pass()
        assertNotNull(result)
    }
}
