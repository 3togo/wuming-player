package com.wuming.tv.core.upnp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RemoteCastCommandTrackerTest {

    @Before
    @After
    public void reset() {
        RemoteCastCommandTracker.resetForTest();
    }

    @Test
    public void duplicatePushIsIgnoredButCurrentSessionCanStop() {
        assertTrue(RemoteCastCommandTracker.accept("one", 1L));
        assertFalse(RemoteCastCommandTracker.accept("one", 2L));
        assertTrue(RemoteCastCommandTracker.canStop("one"));
    }

    @Test
    public void staleStopCannotInterruptNewerCast() {
        assertTrue(RemoteCastCommandTracker.accept("one", 1L));
        assertTrue(RemoteCastCommandTracker.accept("two", 2L));

        assertFalse(RemoteCastCommandTracker.canStop("one"));
        assertTrue(RemoteCastCommandTracker.canStop("two"));
    }

    @Test
    public void expiredSessionIdCanBeUsedAgain() {
        assertTrue(RemoteCastCommandTracker.accept("one", 1L));
        assertTrue(RemoteCastCommandTracker.accept(
                "one", 1L + RemoteCastCommandTracker.SESSION_TTL_MILLIS));
    }

    @Test
    public void javaBlankSemanticsArePreserved() {
        String nonBreakingSpace = "\u00a0";
        assertTrue(RemoteCastCommandTracker.accept(nonBreakingSpace, 1L));
        assertFalse(RemoteCastCommandTracker.accept(nonBreakingSpace, 2L));
    }
}
