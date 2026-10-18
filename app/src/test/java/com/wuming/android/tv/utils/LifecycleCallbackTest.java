package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.wuming.android.tv.impl.Callback;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class LifecycleCallbackTest implements LifecycleOwner {
    @Rule public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();
    private final LifecycleRegistry lifecycle = new LifecycleRegistry(this);

    @Override public Lifecycle getLifecycle() { return lifecycle; }

    @Test
    public void destructionUnsubscribesAndSuppressesLateCompletion() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        AtomicInteger calls = new AtomicInteger();
        Callback callback = LifecycleCallback.wrap(this, counter(calls));
        assertEquals(1, lifecycle.getObserverCount());
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        callback.success();
        callback.error("late failure");
        assertEquals(0, calls.get());
        assertEquals(0, lifecycle.getObserverCount());
    }

    @Test
    public void completionUnsubscribesWithoutWaitingForDestruction() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        AtomicInteger calls = new AtomicInteger();
        Callback callback = LifecycleCallback.wrap(this, counter(calls));
        callback.success();
        callback.success();
        callback.error("late failure");
        assertEquals(1, calls.get());
        assertEquals(0, lifecycle.getObserverCount());
    }

    @Test
    public void alreadyDestroyedOwnerDoesNotReceiveCallbacks() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        AtomicInteger calls = new AtomicInteger();
        LifecycleCallback.wrap(this, counter(calls)).success();
        assertEquals(0, calls.get());
        assertEquals(0, lifecycle.getObserverCount());
    }

    @Test
    public void repeatedDialogActionsRemainActiveUntilDismissal() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
        AtomicInteger calls = new AtomicInteger();
        LifecycleCallback callback = (LifecycleCallback) LifecycleCallback.wrapRepeated(this, counter(calls));
        callback.success();
        callback.success();
        assertEquals(2, calls.get());
        callback.close();
        callback.success();
        assertEquals(2, calls.get());
        assertEquals(0, lifecycle.getObserverCount());
    }

    private static Callback counter(AtomicInteger calls) {
        return new Callback() {
            @Override public void success() { calls.incrementAndGet(); }
            @Override public void error(String error) { calls.incrementAndGet(); }
        };
    }
}
