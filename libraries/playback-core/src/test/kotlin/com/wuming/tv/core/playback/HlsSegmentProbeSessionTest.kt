package com.wuming.tv.core.playback

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class HlsSegmentProbeSessionTest {
    @Test fun overridesRangeAndEncodingWithoutDroppingProviderHeadersAndBoundsEveryRequest() {
        val original = linkedMapOf("Cookie" to "fixture", "range" to "bytes=20-", "accept-encoding" to "gzip")
        var calls = 0
        val session = HlsSegmentProbeSession(original, { _, headers, bytes, timeout ->
            calls++
            assertEquals("fixture", headers["Cookie"])
            assertEquals("bytes=0-65535", headers["Range"])
            assertEquals("identity", headers["Accept-Encoding"])
            assertEquals(3, headers.size)
            assertEquals(65536, bytes)
            assertEquals(3000L, timeout)
            HlsSegmentProbeSession.Prefix(200, null, byteArrayOf())
        })
        repeat(24) { assertNull(session.read("https://media.example/$it.ts")) }
        assertThrows(IOException::class.java) { session.read("https://media.example/25.ts") }
        assertEquals(24, calls)
        assertEquals("bytes=20-", original["range"])
    }

    @Test fun deadlineAndCancellationPreventRequestsOrDiscardLateResults() {
        val time = AtomicLong(0)
        var canceled = false
        var calls = 0
        val session = HlsSegmentProbeSession(emptyMap(), { _, _, _, timeout ->
            calls++
            assertEquals(if (calls == 1) 3000L else 500L, timeout)
            if (calls == 2) time.set(15_000_000_000)
            HlsSegmentProbeSession.Prefix(200, null, byteArrayOf())
        }, { canceled }, time::get)
        session.read("https://media.example/a.ts")
        time.set(14_500_000_000)
        assertThrows(IOException::class.java) { session.read("https://media.example/b.ts") }
        assertThrows(IOException::class.java) { session.read("https://media.example/c.ts") }
        assertEquals(2, calls)
        val canceledSession = HlsSegmentProbeSession(emptyMap(), { _, _, _, _ -> error("must not request") }, { canceled })
        canceled = true
        assertThrows(IOException::class.java) { canceledSession.read("https://media.example/a.ts") }
    }

    @Test fun failedHttpResponsesMalformedRangesAndOversizedPrefixesNeverConfirmVideo() {
        for (prefix in listOf(HlsSegmentProbeSession.Prefix(403, null, byteArrayOf()),
            HlsSegmentProbeSession.Prefix(206, "bytes 20-100/200", byteArrayOf()),
            HlsSegmentProbeSession.Prefix(206, null, byteArrayOf()),
            HlsSegmentProbeSession.Prefix(200, null, ByteArray(65537)))) {
            val session = HlsSegmentProbeSession(emptyMap(), { _, _, _, _ -> prefix })
            if (prefix.status == 403) assertThrows(IOException::class.java) { session.read("https://media.example/a.ts") }
            else assertNull(session.read("https://media.example/a.ts"))
        }
    }
}
