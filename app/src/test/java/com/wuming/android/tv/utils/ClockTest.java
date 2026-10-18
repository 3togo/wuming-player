package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;

import com.wuming.tv.core.design.clock.ClockTicker;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

public class ClockTest {

    @Test
    public void replacingCallbackStopsDeliveringToPreviousOwner() throws Exception {
        Clock clock = Clock.create();
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger newCalls = new AtomicInteger();
        clock.setCallback(time -> oldCalls.incrementAndGet());
        clock.setCallback(time -> newCalls.incrementAndGet());

        tick(clock);

        assertEquals(0, oldCalls.get());
        assertEquals(1, newCalls.get());
    }

    @Test
    public void clearingCallbackUnsubscribesOwner() throws Exception {
        Clock clock = Clock.create();
        AtomicInteger calls = new AtomicInteger();
        clock.setCallback(time -> calls.incrementAndGet());
        clock.setCallback(null);

        tick(clock);

        assertEquals(0, calls.get());
    }

    @Test
    public void releaseClearsCallbackEvenWhenClockIsReused() throws Exception {
        Clock clock = Clock.create();
        AtomicInteger calls = new AtomicInteger();
        clock.setCallback(time -> calls.incrementAndGet());
        clock.release();
        clock.release();

        tick(clock);

        assertEquals(0, calls.get());
    }

    @Test
    public void stopPreservesCallbackForResume() throws Exception {
        Clock clock = Clock.create();
        AtomicInteger calls = new AtomicInteger();
        clock.setCallback(time -> calls.incrementAndGet());
        clock.stop();

        tick(clock);

        assertEquals(1, calls.get());
    }

    // Drive the real ticker without requiring an Android main looper.
    private static void tick(Clock clock) throws Exception {
        Field field = Clock.class.getDeclaredField("ticker");
        field.setAccessible(true);
        ClockTicker ticker = (ClockTicker) field.get(clock);
        ticker.start();
        ticker.tick();
        ticker.stop();
    }
}
