package com.wuming.android.tv.player.exo

import androidx.media3.common.C
import org.junit.Assert.*
import org.junit.Test

class HdrDisplayCheckerTest {
    @Test fun hdrTypesUseAndroidValuesWithoutConfusingDolbyVisionOrHlg() {
        assertEquals(2, HdrDisplayChecker.HDR_TYPE_HDR10)
        assertEquals(3, HdrDisplayChecker.HDR_TYPE_HLG)
        assertEquals(4, HdrDisplayChecker.HDR_TYPE_HDR10_PLUS)
        assertTrue(HdrDisplayChecker.isColorTransferSupported(C.COLOR_TRANSFER_ST2084, setOf(2)))
        assertTrue(HdrDisplayChecker.isColorTransferSupported(C.COLOR_TRANSFER_ST2084, setOf(4)))
        assertFalse(HdrDisplayChecker.isColorTransferSupported(C.COLOR_TRANSFER_ST2084, setOf(1, 3)))
        assertTrue(HdrDisplayChecker.isColorTransferSupported(C.COLOR_TRANSFER_HLG, setOf(3)))
        assertFalse(HdrDisplayChecker.isColorTransferSupported(C.COLOR_TRANSFER_HLG, setOf(2)))
    }

    @Test fun sdrRemainsEligibleOnDisplaysWithoutHdr() {
        assertTrue(HdrDisplayChecker.isColorTransferSupported(C.COLOR_TRANSFER_SDR, emptySet()))
        assertFalse(HdrDisplayChecker.isColorTransferSupported(C.COLOR_TRANSFER_ST2084, emptySet()))
        assertFalse(HdrDisplayChecker.isColorTransferSupported(C.COLOR_TRANSFER_HLG, emptySet()))
    }
}
