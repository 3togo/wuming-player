package com.wuming.tv.core.playback.renderer

import org.junit.Assert.*
import org.junit.Test

class RendererProbeSamplesTest {
    @Test fun allSamplesAndReferencedSegmentsArePackaged() {
        RendererProbeSamples.samples.forEach { assertNotNull(RendererProbeSamples.resource(it.file)) }
        val hls = String(RendererProbeSamples.resource("hls.m3u8")!!)
        hls.lines().filter { it.isNotBlank() && !it.startsWith('#') }.forEach { assertNotNull(RendererProbeSamples.resource(it)) }
        for (stream in 0..1) {
            assertNotNull(RendererProbeSamples.resource("init-stream$stream.m4s"))
            for (segment in 1..8) assertNotNull(RendererProbeSamples.resource("chunk-stream$stream-${"%05d".format(segment)}.m4s"))
        }
        assertNull(RendererProbeSamples.resource("../renderer-capability-rules.json"))
    }
    @Test fun explicitPictureAndSoundConfirmationRecordsOnlyTheSampleCodecs() {
        var stored: String? = null
        val store = object : RendererProfileStore { override fun read() = stored; override fun write(json: String) { stored = json } }
        val db = RendererProfiles(store)
        val tv = RendererIdentity(RendererIdentity.key("tv"), "TV", "vendor", "model", "unknown", "AVTransport")
        RendererProbeSamples.recordConfirmed(db, tv, RendererProbeSamples.samples[0], "test", "run1")
        val reopened = RendererProfiles(store)
        listOf(RendererCapability.MP4, RendererCapability.H264, RendererCapability.AAC).forEach {
            assertEquals(CapabilityState.SUPPORTED, reopened.state(tv, it))
        }
        assertEquals(CapabilityState.UNKNOWN, reopened.state(tv, RendererCapability.HEVC))
        assertEquals(CapabilityState.UNKNOWN, reopened.state(tv, RendererCapability.HLS))
    }
}
