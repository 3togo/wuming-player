package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class ClockCorrectionRecoveryTest {
    @Test fun retriesFailureOnceAfterCorrection() {
        val recovery = ClockCorrectionRecovery()
        recovery.started()
        assertFalse(recovery.completed(true))
        assertTrue(recovery.clockCorrected())
        assertFalse(recovery.clockCorrected())
    }

    @Test fun correctionDuringFailureIsRemembered() {
        val recovery = ClockCorrectionRecovery()
        recovery.started()
        assertFalse(recovery.clockCorrected())
        assertTrue(recovery.completed(true))
        recovery.started()
        assertFalse(recovery.completed(true))
    }

    @Test fun successfulAndSupersededRequestsAreNotRetried() {
        val recovery = ClockCorrectionRecovery()
        recovery.started()
        assertFalse(recovery.clockCorrected())
        assertFalse(recovery.completed(false))
        assertFalse(recovery.clockCorrected())
        recovery.started()
        recovery.completed(true)
        recovery.cancel()
        assertFalse(recovery.clockCorrected())
    }
}
