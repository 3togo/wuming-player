package com.wuming.tv.core.playback

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class HardwareCapabilityJsonTest {
    private val report = HardwareCapabilityReport(123L, "device", listOf(SavedVideoDecoder(
        "decoder", "video/avc", listOf(SavedCodecProfile(8, 2048)),
        listOf(SavedVideoMode(1920, 1080, 60.0)), 20000000, true, false, true)), emptySet())

    @Test fun readsCanonicalReportAndRejectsMissingOrObfuscatedFields() {
        val json = Gson().toJson(report)
        assertEquals(report, HardwareCapabilityJson.decode(json))
        for (key in listOf("detectedAt", "device", "decoders", "hdrTypes", "profiles", "modes", "mime")) {
            assertNull(key, HardwareCapabilityJson.decode(json.replace("\"$key\":", "\"obfuscated\":")))
        }
        assertNull(HardwareCapabilityJson.decode(null))
        assertNull(HardwareCapabilityJson.decode("{}"))
        assertNull(HardwareCapabilityJson.decode("{broken"))
        assertNull(HardwareCapabilityJson.decode(json.replace("\"decoders\":[", "\"decoders\":null,\"ignored\":[")))
    }
}
