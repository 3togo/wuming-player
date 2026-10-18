package com.wuming.android.tv.ui.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PlaybackNetworkObserverTest {
    @Test public void burstsCoalesceAndStopRemovesPendingUpdates() {
        Fixture f = new Fixture();
        f.observer.start();
        f.observer.start();
        assertEquals(1, f.callbacks.size());
        f.callbacks.get(0).run();
        f.callbacks.get(0).run();
        assertEquals(1, f.queue.size());
        Runnable alreadyDequeued = f.queue.get(0);
        f.observer.stop();
        f.callbacks.get(0).run();
        alreadyDequeued.run();
        assertTrue(f.queue.isEmpty());
        assertEquals(0, f.updates.get());
        assertEquals(1, f.unregistered.get());
    }

    @Test public void oldSessionCannotRefreshRestartedScreen() {
        Fixture f = new Fixture();
        f.observer.start();
        Runnable oldCallback = f.callbacks.get(0);
        oldCallback.run();
        Runnable oldDelivery = f.queue.get(0);
        f.observer.stop();
        f.observer.start();
        oldCallback.run();
        oldDelivery.run();
        assertTrue(f.queue.isEmpty());
        f.callbacks.get(1).run();
        f.queue.remove(0).run();
        assertEquals(1, f.updates.get());
        f.observer.close();
        f.observer.close();
        f.observer.start();
        assertEquals(2, f.callbacks.size());
        assertEquals(2, f.unregistered.get());
    }

    @Test public void unavailableRegistrationCanBeRetriedAndLeavesNoQueuedCallback() {
        List<Runnable> queue = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        PlaybackNetworkObserver observer = new PlaybackNetworkObserver(changed -> {
            attempts.incrementAndGet();
            changed.run();
            return null;
        }, queue::add, queue::remove, () -> { throw new AssertionError("Unavailable subscription delivered"); });
        observer.start();
        observer.start();
        assertEquals(2, attempts.get());
        assertTrue(queue.isEmpty());
        observer.close();
    }

    private static class Fixture {
        final List<Runnable> callbacks = new ArrayList<>();
        final List<Runnable> queue = new ArrayList<>();
        final AtomicInteger updates = new AtomicInteger();
        final AtomicInteger unregistered = new AtomicInteger();
        final PlaybackNetworkObserver observer = new PlaybackNetworkObserver(changed -> {
            callbacks.add(changed);
            return unregistered::incrementAndGet;
        }, queue::add, queue::remove, updates::incrementAndGet);
    }
}
