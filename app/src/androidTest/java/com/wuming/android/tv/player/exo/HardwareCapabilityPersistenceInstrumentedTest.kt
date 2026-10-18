package com.wuming.android.tv.player.exo

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.RendererCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wuming.tv.core.playback.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareCapabilityPersistenceInstrumentedTest {
    private val report = HardwareCapabilityReport(123L, "device-v1", listOf(SavedVideoDecoder(
        "decoder", "video/avc", listOf(SavedCodecProfile(8, 2048)),
        listOf(SavedVideoMode(1920, 1080, 60.0)), 20000000, true, false, true)), emptySet())

    @Test fun detectionRunsOnlyOnExplicitRequestAndSurvivesAppOrSystemVersionChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("hardware_report_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            var detections = 0
            val cache = VideoCapabilityCache(prefs, "version-1") { detections++; report }
            assertNull(cache.savedReport())
            assertEquals(0, detections)
            assertEquals(1, cache.refresh())
            val reopened = VideoCapabilityCache(prefs, "version-2") {
                detections++
                report.copy(detectedAt = 456L)
            }
            assertEquals(report, reopened.savedReport())
            val format = Format.Builder().setSampleMimeType("video/avc").setCodecs("avc1.640028")
                .setWidth(1920).setHeight(1080).setFrameRate(50f).build()
            repeat(3) {
                val flags = reopened.resolve(format) { throw AssertionError("Saved capability must avoid another query") }
                assertEquals(C.FORMAT_HANDLED, RendererCapabilities.getFormatSupport(flags))
            }
            assertEquals(1, detections)
            reopened.refresh()
            assertEquals(2, detections)
            assertEquals(456L, reopened.savedReport()!!.detectedAt)
        } finally {
            prefs.edit().clear().commit()
        }
    }

    @Test fun failedDetectionPreservesTheLastSuccessfulReport() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("hardware_report_failure_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            VideoCapabilityCache(prefs, "version-1") { report }.refresh()
            val failed = VideoCapabilityCache(prefs, "version-2") { throw IllegalStateException("probe failed") }
            assertThrows(IllegalStateException::class.java) { failed.refresh() }
            assertEquals(report, VideoCapabilityCache(prefs, "version-3").savedReport())
        } finally {
            prefs.edit().clear().commit()
        }
    }
}
