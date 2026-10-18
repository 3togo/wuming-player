package com.wuming.android.tv.server;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NanoTest {

    @Test
    public void clientThreadLimitLeavesControlCapacityAboveParallelMediaRanges() {
        assertEquals(48, Nano.clientThreadLimit(1));
        assertEquals(48, Nano.clientThreadLimit(6));
        assertEquals(64, Nano.clientThreadLimit(8));
        assertEquals(96, Nano.clientThreadLimit(16));
    }
}
