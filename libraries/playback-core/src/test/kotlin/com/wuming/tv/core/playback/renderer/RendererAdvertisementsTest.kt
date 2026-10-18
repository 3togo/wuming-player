package com.wuming.tv.core.playback.renderer

import org.junit.Assert.*
import org.junit.Test

class RendererAdvertisementsTest {
    @Test fun failedFormatQueryRetainsFormatsAndRefreshesIndependentActionDeclarations() {
        var saved: String? = null
        val store = object : RendererProfileStore {
            override fun read() = saved
            override fun write(json: String) { saved = json }
        }
        val tv = RendererIdentity(RendererIdentity.key("denied-tv"), "TV", "Vendor", "Model", "unknown", "services")
        val db = RendererProfiles(store)
        db.advertised(tv, setOf(RendererCapability.MP4, RendererCapability.SEEK), "test")
        db.record(tv, RendererEvidence(RendererCapability.H264, EvidenceResult.CONTROLLED_SUCCESS, "confirmed", 1, "test", "test"))
        db.advertisedActions(tv, setOf("Stop"), "test")
        val restored = RendererProfiles(store)
        val report = com.google.gson.JsonParser.parseString(restored.export(tv)).asJsonObject
        val assessments = report.getAsJsonObject("assessments")
        assertTrue(assessments.getAsJsonObject("MP4").get("advertised").asBoolean)
        assertTrue(assessments.getAsJsonObject("STOP").get("advertised").asBoolean)
        assertFalse(assessments.getAsJsonObject("SEEK").get("advertised").asBoolean)
        assertEquals(CapabilityState.SUPPORTED, restored.state(tv, RendererCapability.H264))
        assertEquals(CapabilityState.UNKNOWN, restored.state(tv, RendererCapability.STOP))
        assertEquals("not_tested", assessments.getAsJsonObject("HEVC").get("unknownReason").asString)
        db.record(tv, RendererEvidence(RendererCapability.DASH, EvidenceResult.PROGRESS, "first", 1, "test", "test"))
        db.record(tv, RendererEvidence(RendererCapability.H264, EvidenceResult.CONTROLLED_FAILURE, "failed", 2, "test", "test"))
        val updated = com.google.gson.JsonParser.parseString(db.export(tv)).asJsonObject.getAsJsonObject("assessments")
        assertEquals("insufficient_evidence", updated.getAsJsonObject("DASH").get("unknownReason").asString)
        assertEquals("conflicting_evidence", updated.getAsJsonObject("H264").get("unknownReason").asString)
    }
    @Test fun parsesSinkMimeAndExplicitProfilesWithoutInventingCodecSupport() {
        val caps = RendererAdvertisements.parse("http-get:*:video/mp4:*,http-get:*:application/dash+xml:*,http-get:*:application/vnd.apple.mpegurl:*", setOf("Seek", "Stop"))
        assertEquals(setOf(RendererCapability.MP4, RendererCapability.DASH, RendererCapability.HLS, RendererCapability.SEEK, RendererCapability.STOP), caps)
        assertEquals(setOf(RendererCapability.MP4, RendererCapability.H264, RendererCapability.AAC),
            RendererAdvertisements.parse("http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_HD_720p_AAC;DLNA.ORG_OP=01", emptySet()))
    }
    @Test fun wildcardMalformedAndOtherTransportsDoNotBecomeSupport() {
        assertTrue(RendererAdvertisements.parse("http-get:*:*:*,broken,rtsp-rtp-udp:*:video/h264:*", setOf("Play")).isEmpty())
    }
    @Test fun advertisementsPersistRefreshAndNeverOverrideMeasuredFailures() {
        var saved: String? = null
        val store = object : RendererProfileStore {
            override fun read() = saved
            override fun write(json: String) { saved = json }
        }
        val tv = RendererIdentity(RendererIdentity.key("tv"), "TV", "Vendor", "Model", "unknown", "services")
        val db = RendererProfiles(store)
        db.advertised(tv, setOf(RendererCapability.MP4), "test")
        assertTrue(RendererProfiles(store).export(tv).contains("\"advertised\": true"))
        assertEquals(CapabilityState.UNKNOWN, db.state(tv, RendererCapability.MP4))
        repeat(2) { db.record(tv, RendererEvidence(RendererCapability.MP4, EvidenceResult.CONTROLLED_FAILURE, "$it", 1, "test", "test")) }
        assertEquals(CapabilityState.UNSUPPORTED, db.state(tv, RendererCapability.MP4))
        db.advertised(tv, emptySet(), "test")
        assertFalse(db.export(tv).contains("\"advertised\": true"))
        assertEquals(CapabilityState.UNSUPPORTED, db.state(tv, RendererCapability.MP4))
    }
}
