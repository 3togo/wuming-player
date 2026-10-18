package com.wuming.android.tv.cloud.opendrive

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ExternalEpisodePayloadTest {

    private fun objectOf(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun parsesDirectJsonObjectOnly() {
        assertEquals("1", ExternalEpisodePayload.json("""{"a":"1"}""", false)!!["a"].asString)
        assertNull(ExternalEpisodePayload.json("[1,2]", false))
        assertNull(ExternalEpisodePayload.json("not json", false))
        assertNull(ExternalEpisodePayload.json("   ", false))
        assertNull(ExternalEpisodePayload.json(null, false))
    }

    @Test
    fun decodesBase64OnlyWhenTheTextItselfIsNotAnObject() {
        // A payload that already parses wins outright, even with the base64 flag enabled.
        assertEquals(
            "1",
            ExternalEpisodePayload.json("""{"a":"1"}""", true)!!["a"].asString,
        )
        assertNull(ExternalEpisodePayload.json("""{"a":""", true))
    }

    @Test
    fun rejectsPayloadOverMaxLength() {
        val oversized = "{\"a\":\"" + "x".repeat(ExternalEpisodePayload.MAX_LENGTH) + "\"}"
        assertTrue(oversized.length > ExternalEpisodePayload.MAX_LENGTH)
        assertNull(ExternalEpisodePayload.json(oversized, true))
    }

    @Test
    fun decodesUnpaddedUrlSafeBase64WhenEnabled() {
        val payload = """{"x":1}"""
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        // The encoder drops the pad character, so the decoder has to restore the alignment.
        assertTrue(encoded.length % 4 != 0)
        assertNull(ExternalEpisodePayload.json(encoded, false))
        val decoded = ExternalEpisodePayload.json(encoded, true)
        assertNotNull(decoded)
        assertEquals(1, decoded!!["x"].asInt)
    }

    @Test
    fun decodesUrlSafeAlphabetWithoutManglingValue() {
        val payload = """{"k":"?>~"}"""
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        assertEquals("?>~", ExternalEpisodePayload.json(encoded, true)!!["k"].asString)
    }

    @Test
    fun returnsNullForUndecodableBase64() {
        assertNull(ExternalEpisodePayload.json("!!!not-base64!!!", true))
    }

    @Test
    fun belongsToMatchesFlagAndNamedProviderFields() {
        assertTrue(ExternalEpisodePayload.belongsTo("Quark 智", null, "quark"))
        assertTrue(ExternalEpisodePayload.belongsTo(null, objectOf("""{"provider":"AliPan"}"""), "alipan"))
        assertTrue(ExternalEpisodePayload.belongsTo("", objectOf("""{"pan":"123盘"}"""), "123盘"))
        assertFalse(ExternalEpisodePayload.belongsTo("uc", objectOf("""{"flag":"baidu"}"""), "quark"))
        assertFalse(ExternalEpisodePayload.belongsTo(null, null, "quark"))
    }

    @Test
    fun belongsToIgnoresStructuredMembers() {
        assertTrue(
            ExternalEpisodePayload.belongsTo(
                null,
                objectOf("""{"flag":123,"drive":true,"provider":"quark"}"""),
                "quark",
            ),
        )
        // Numbers and booleans are JSON primitives, so their text form is a legitimate label.
        assertTrue(ExternalEpisodePayload.belongsTo(null, objectOf("""{"flag":123}"""), "123"))
        assertFalse(ExternalEpisodePayload.belongsTo(null, objectOf("""{"flag":{"a":1}}"""), "1"))
    }

    @Test
    fun labelsProviderFromRouteText() {
        assertEquals("alipan", ExternalEpisodePayload.labeledProviderId("阿里云盘"))
        assertEquals("quark", ExternalEpisodePayload.labeledProviderId("夸父智"))
        assertEquals("uc", ExternalEpisodePayload.labeledProviderId("优汐"))
        assertEquals("baidu", ExternalEpisodePayload.labeledProviderId("BD原画"))
        assertEquals("123pan", ExternalEpisodePayload.labeledProviderId("123云"))
        assertEquals("tianyi", ExternalEpisodePayload.labeledProviderId("天翼"))
        assertNull(ExternalEpisodePayload.labeledProviderId("普通"))
        assertNull(ExternalEpisodePayload.labeledProviderId(null))
    }

    @Test
    fun prefersTheFirstLabeledProvider() {
        assertEquals("alipan", ExternalEpisodePayload.labeledProviderId("aliyun/quark"))
    }

    @Test
    fun firstReturnsFirstUsableValue() {
        val payload = objectOf("""{"a":"","b":"  ","c":"ok","d":"second"}""")
        assertEquals("ok", ExternalEpisodePayload.first(payload, "a", "b", "c", "d"))
        assertEquals("", ExternalEpisodePayload.first(payload))
        assertEquals("", ExternalEpisodePayload.first(null, "c"))
    }

    @Test
    fun firstRejectsUnsafeValues() {
        val long = "x".repeat(4097)
        assertEquals("", ExternalEpisodePayload.first(objectOf("""{"a":"$long"}"""), "a"))
        assertEquals("", ExternalEpisodePayload.first(objectOf("""{"a":"line\nbreak"}"""), "a"))
        assertEquals("", ExternalEpisodePayload.first(objectOf("""{"a":{"b":"c"}}"""), "a"))
        assertEquals("", ExternalEpisodePayload.first(objectOf("""{"a":null}"""), "a"))
    }

    @Test
    fun numberFallsBackToMinusOne() {
        val payload = objectOf("""{"size":"1024","bad":"abc"}""")
        assertEquals(1024L, ExternalEpisodePayload.number(payload, "size"))
        assertEquals(-1L, ExternalEpisodePayload.number(payload, "bad"))
        assertEquals(-1L, ExternalEpisodePayload.number(payload, "missing"))
    }

    @Test
    fun identifierAcceptsOpaqueHandlesOnly() {
        assertTrue(ExternalEpisodePayload.identifier("a"))
        assertTrue(ExternalEpisodePayload.identifier("Ab-9_"))
        assertTrue(ExternalEpisodePayload.identifier("a".repeat(256)))
        assertFalse(ExternalEpisodePayload.identifier(""))
        assertFalse(ExternalEpisodePayload.identifier(null))
        assertFalse(ExternalEpisodePayload.identifier("has space"))
        assertFalse(ExternalEpisodePayload.identifier("a".repeat(257)))
    }

    @Test
    fun capabilityGuardsLengthAndControlCharacters() {
        assertTrue(ExternalEpisodePayload.capability("x".repeat(4096)))
        assertFalse(ExternalEpisodePayload.capability("   "))
        assertFalse(ExternalEpisodePayload.capability(null))
        assertFalse(ExternalEpisodePayload.capability("a\tb"))
    }

    @Test
    fun splitHonoursMinimumAndMaximumParts() {
        assertTrue(ExternalEpisodePayload.split("a:b:c", ":", 2)!!.contentEquals(arrayOf("a", "b", "c")))
        assertNull(ExternalEpisodePayload.split("a:b:c", ":", 4))
        assertTrue(ExternalEpisodePayload.split("a:b:c", ":", 0)!!.size == 3)
        assertNull(ExternalEpisodePayload.split(null, ":", 1))
        assertNull(ExternalEpisodePayload.split("   ", ":", 1))
        assertNull(ExternalEpisodePayload.split("a:".repeat(16), ":", 1))
        assertTrue(ExternalEpisodePayload.split("a:".repeat(15) + "a", ":", 2)!!.size == 16)
    }

    @Test
    fun splitKeepsTrailingEmptyParts() {
        assertEquals(3, ExternalEpisodePayload.split("a:b:", ":", 2)!!.size)
    }

    @Test
    fun splitTreatsDelimiterAsRegex() {
        assertTrue(ExternalEpisodePayload.split("a1b2c", "\\d", 2)!!.contentEquals(arrayOf("a", "b", "c")))
    }
}
