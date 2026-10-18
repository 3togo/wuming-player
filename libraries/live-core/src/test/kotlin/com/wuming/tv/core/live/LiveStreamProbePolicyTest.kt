package com.wuming.tv.core.live

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamProbePolicyTest {
    @Test
    fun successfulResponseNeedsActualData() {
        assertTrue(LiveStreamProbePolicy.hasPayload(206, ByteArrayInputStream(byteArrayOf(0))))
        assertFalse(LiveStreamProbePolicy.hasPayload(200, ByteArrayInputStream(byteArrayOf())))
        assertFalse(LiveStreamProbePolicy.hasPayload(204, null))
    }

    @Test
    fun errorAndUnresolvedRedirectBodiesAreNotStreams() {
        val unreadable = object : InputStream() {
            override fun read(): Int = throw AssertionError("Must not read a rejected response")
        }
        assertFalse(LiveStreamProbePolicy.hasPayload(403, unreadable))
        assertFalse(LiveStreamProbePolicy.hasPayload(302, unreadable))
    }

    @Test(expected = IOException::class)
    fun readFailureCannotBecomeAHealthyMeasurement() {
        LiveStreamProbePolicy.hasPayload(200, object : InputStream() {
            override fun read(): Int = throw IOException("Connection closed")
        })
    }
}
