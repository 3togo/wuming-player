package com.wuming.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DepotPruneTest {

    @Test
    public void resetsFailCountOnSuccess() {
        // A working source must not accumulate prior failures.
        assertEquals(0, DepotPrune.nextFailCount(5, true));
    }

    @Test
    public void incrementsFailCountOnFailure() {
        assertEquals(1, DepotPrune.nextFailCount(0, false));
        assertEquals(2, DepotPrune.nextFailCount(1, false));
        assertEquals(DepotPrune.PRUNE_AFTER_FAILS, DepotPrune.nextFailCount(DepotPrune.PRUNE_AFTER_FAILS - 1, false));
    }

    @Test
    public void keepsEntryThroughEveryFailureBelowThreshold() {
        // The whole point: a single (or a couple of) failed fetches must never delete a source.
        for (int i = 0; i < DepotPrune.PRUNE_AFTER_FAILS - 1; i++) {
            assertTrue("failCount=" + i + " should be kept", DepotPrune.keep(i));
        }
    }

    @Test
    public void prunesOnlyAtThreshold() {
        assertFalse(DepotPrune.keep(DepotPrune.PRUNE_AFTER_FAILS));
        assertFalse(DepotPrune.keep(DepotPrune.PRUNE_AFTER_FAILS + 1));
    }

    @Test
    public void consecutiveSuccessThenFailureStartsOver() {
        int afterSuccess = DepotPrune.nextFailCount(DepotPrune.PRUNE_AFTER_FAILS - 1, true);
        assertEquals(0, afterSuccess);
        assertTrue(DepotPrune.keep(afterSuccess));
        int afterNextFail = DepotPrune.nextFailCount(afterSuccess, false);
        assertEquals(1, afterNextFail);
        assertTrue(DepotPrune.keep(afterNextFail));
    }
}
