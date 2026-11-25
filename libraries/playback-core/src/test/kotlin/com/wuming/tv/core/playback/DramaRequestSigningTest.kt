package com.wuming.tv.core.playback

import com.google.gson.JsonParser
import com.wuming.tv.core.base.Hex
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class DramaRequestSigningTest {
    @Test fun sm3MatchesPublishedSingleAndMultiBlockVectors() {
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", Hex.encodeLower(DramaWire.sm3("abc".toByteArray())))
        assertEquals("debe9ff92275b8a138604889c18e5a4d6fdb70e5387e5765293dcba39c0c5732", Hex.encodeLower(DramaWire.sm3("abcd".repeat(16).toByteArray())))
    }

    @Test fun syntheticWireFixturesCoverEveryTimestampCipherMode() {
        val fixtures = javaClass.getResourceAsStream("drama-signing-fixtures.json")!!.bufferedReader().use { JsonParser.parseReader(it).asJsonArray }
        assertEquals(4, fixtures.size())
        val fields = linkedMapOf("device_id" to "123456", "version_name" to "7.3.9.32", "channel" to "fixture channel",
            "os_version" to "12", "device_type" to "HD1900", "escaped" to "a b/中文%")
        for (fixture in fixtures) {
            val f = fixture.asJsonObject
            val signed = DramaRequestSigning(Random(7), 1700000000000L).sign(fields, "wire-fixture".toByteArray(), f.get("now").asLong)
            assertEquals(f.get("query").asString, signed.query)
            for ((header, expected) in f.getAsJsonObject("headerSha256").entrySet()) {
                assertEquals("$header at ${f.get("now")}", expected.asString,
                    Hex.encodeLower(DramaWire.digest("SHA-256", signed.headers.getValue(header).toByteArray())))
            }
            assertEquals("SignedDramaRequest", signed.toString())
        }
    }

    @Test fun modifyingTheCompressedBodyChangesItsAuthentication() {
        val fields = linkedMapOf("device_id" to "123456", "version_name" to "7.3.9.32", "channel" to "fixture",
            "os_version" to "12", "device_type" to "HD1900")
        fun sign(body: String) = DramaRequestSigning(Random(7), 1700000000000L).sign(fields, body.toByteArray(), 1700000000000L)
        val first = sign("first"); val second = sign("second")
        assertNotEquals(first.headers["x-ss-stub"], second.headers["x-ss-stub"])
        assertNotEquals(first.headers["x-medusa"], second.headers["x-medusa"])
    }
}
