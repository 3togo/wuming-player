package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class LifecycleCleanupTest implements LifecycleOwner {
    @Rule public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);

    @Override public Lifecycle getLifecycle() { return lifecycle; }

    @Test public void ownerDestructionClosesResourceExactlyOnce() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        AtomicInteger calls = new AtomicInteger();
        LifecycleCleanup cleanup = new LifecycleCleanup(this, calls::incrementAndGet);
        assertEquals(1, lifecycle.getObserverCount());
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        cleanup.onDestroy(this);
        cleanup.close();
        assertEquals(1, calls.get());
        assertEquals(0, lifecycle.getObserverCount());
    }

    @Test public void earlyCompletionRemovesRegistrationWithoutRunningCleanupAgain() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        AtomicInteger calls = new AtomicInteger();
        LifecycleCleanup cleanup = new LifecycleCleanup(this, calls::incrementAndGet);
        cleanup.close();
        cleanup.close();
        assertEquals(0, lifecycle.getObserverCount());
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        assertEquals(0, calls.get());
    }

    @Test public void destroyedOwnerImmediatelyClosesNewResource() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        AtomicInteger calls = new AtomicInteger();
        new LifecycleCleanup(this, calls::incrementAndGet).close();
        assertEquals(1, calls.get());
        assertEquals(0, lifecycle.getObserverCount());
    }
}
