package com.wuming.tv.core.source;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DepotPrunePolicyTest {

    @Test
    public void resetsFailCountOnSuccess() {
        // A working source must not accumulate prior failures.
        assertEquals(0, DepotPrunePolicy.nextFailCount(5, true));
    }

    @Test
    public void incrementsFailCountOnFailure() {
        assertEquals(1, DepotPrunePolicy.nextFailCount(0, false));
        assertEquals(2, DepotPrunePolicy.nextFailCount(1, false));
        assertEquals(DepotPrunePolicy.PRUNE_AFTER_FAILS, DepotPrunePolicy.nextFailCount(DepotPrunePolicy.PRUNE_AFTER_FAILS - 1, false));
    }

    @Test
    public void keepsEntryThroughEveryFailureBelowThreshold() {
        // The whole point: a single (or a couple of) failed fetches must never delete a source.
        for (int i = 0; i < DepotPrunePolicy.PRUNE_AFTER_FAILS - 1; i++) {
            assertTrue("failCount=" + i + " should be kept", DepotPrunePolicy.keep(i));
        }
    }

    @Test
    public void prunesOnlyAtThreshold() {
        assertFalse(DepotPrunePolicy.keep(DepotPrunePolicy.PRUNE_AFTER_FAILS));
        assertFalse(DepotPrunePolicy.keep(DepotPrunePolicy.PRUNE_AFTER_FAILS + 1));
    }

    @Test
    public void consecutiveSuccessThenFailureStartsOver() {
        int afterSuccess = DepotPrunePolicy.nextFailCount(DepotPrunePolicy.PRUNE_AFTER_FAILS - 1, true);
        assertEquals(0, afterSuccess);
        assertTrue(DepotPrunePolicy.keep(afterSuccess));
        int afterNextFail = DepotPrunePolicy.nextFailCount(afterSuccess, false);
        assertEquals(1, afterNextFail);
        assertTrue(DepotPrunePolicy.keep(afterNextFail));
    }
}
