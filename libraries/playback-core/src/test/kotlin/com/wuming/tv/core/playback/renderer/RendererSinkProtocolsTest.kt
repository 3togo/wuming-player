package com.wuming.tv.core.playback.renderer

import org.junit.Assert.*
import org.junit.Test

class RendererSinkProtocolsTest {
    @Test fun playbackFormatsRoundTripWithoutClaimingCodecOrTransportEvidence() {
        val formats = setOf(RendererCapability.MP4, RendererCapability.HLS, RendererCapability.DASH)
        val sink = RendererSinkProtocols.forFormats(formats)
        assertFalse(sink.isBlank())
        assertEquals(formats, RendererAdvertisements.parse(sink, emptySet()))
        assertTrue(sink.contains("http-get:*:application/dash+xml:*"))
    }

    @Test fun unconfiguredFormatsAndNonFormatFlagsAreNotAdvertised() {
        assertEquals("", RendererSinkProtocols.forFormats(emptySet()))
        assertEquals("", RendererSinkProtocols.forFormats(setOf(RendererCapability.HEVC, RendererCapability.H264,
            RendererCapability.AAC, RendererCapability.SEEK, RendererCapability.BYTE_RANGES, RendererCapability.CHUNKED)))
        assertEquals(setOf(RendererCapability.MP4), RendererAdvertisements.parse(
            RendererSinkProtocols.forFormats(setOf(RendererCapability.MP4)), emptySet()))
    }
}
