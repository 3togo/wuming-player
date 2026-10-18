package com.wuming.tv.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudContractsTest {

    @Test
    fun fileAndPlaybackSnapshotsDefensivelyNormalizeValues() {
        val item = OpenDriveItem("id", "", false, -1)
        val playback = OpenDrivePlayback("https://media", mapOf("X-Test" to "1"), -1)

        assertEquals("Cloud file", item.name)
        assertEquals(0L, item.size)
        assertEquals(0L, playback.expiresAtEpochMillis)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (playback.headers as MutableMap<String, String>).put("X-New", "2")
        }
    }

    @Test
    fun accountPolicyRequiresAvailabilityAndVerifiedState() {
        val verified = OpenDriveAccountSnapshot("drive", "Drive", true, true, true, OpenDriveTokenState.VERIFIED)
        val invalid = OpenDriveAccountSnapshot("drive", "Drive", true, true, true, OpenDriveTokenState.INVALID)

        assertTrue(verified.verified())
        assertFalse(verified.canStartLogin())
        assertTrue(invalid.canStartLogin())
    }
}
