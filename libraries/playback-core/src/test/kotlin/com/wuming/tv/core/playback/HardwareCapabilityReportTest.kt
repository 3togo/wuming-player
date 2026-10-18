package com.wuming.tv.core.playback

import com.google.gson.Gson
import com.wuming.tv.core.playback.VideoRenditionPolicy.Support.*
import org.junit.Assert.*
import org.junit.Test

class HardwareCapabilityReportTest {
    private val report = HardwareCapabilityReport(123L, "device", listOf(SavedVideoDecoder(
        "decoder", "video/avc", listOf(SavedCodecProfile(8, 2048)),
        listOf(SavedVideoMode(1920, 1080, 60.0)), 20000000, true, false, true)), emptySet())

    @Test fun reportSurvivesSerializationWithProfilesRatesAndDate() {
        assertEquals(report, Gson().fromJson(Gson().toJson(report), HardwareCapabilityReport::class.java))
    }

    @Test fun savedEvidenceDistinguishesSupportedExceedingUnknownAndUnsupported() {
        assertEquals(SUPPORTED, report.assess("video/avc", 8, 2048, 1920, 1080, 50f, 15000000))
        assertEquals(EXCEEDS, report.assess("video/avc", 8, 2048, 1920, 1080, 120f, 15000000))
        assertEquals(UNKNOWN, report.assess("video/avc", 8, 2048, 1920, 800, 50f, 15000000))
        assertEquals(UNSUPPORTED, report.assess("video/hevc", 2, 512, 3840, 2160, 50f, 15000000))
        assertEquals(EXCEEDS, report.assess("video/avc", 8, 4096, 1920, 1080, 30f, 10000000))
        assertEquals(UNSUPPORTED, report.assess("video/avc", 16, 2048, 1920, 1080, 30f, 10000000))
    }
}
