package com.wuming.android.tv.utils;

import static org.junit.Assert.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

public class UiRequestTest {
    @Rule public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    @Test public void successTransfersResultAndUnregistersLifecycle() {
        Fixture f = new Fixture();
        UiRequest<String> request = f.start(() -> "result");
        f.drain();
        assertEquals(List.of("result"), f.results);
        assertTrue(f.discarded.isEmpty());
        assertEquals(0, f.lifecycle.getObserverCount());
        request.close();
        f.destroy();
        assertEquals(1, f.cancelled);
    }

    @Test public void destroyedOwnerNeverStartsOperation() {
        Fixture f = new Fixture();
        f.destroy();
        f.start(() -> { throw new AssertionError("Started after destruction"); });
        assertTrue(f.work.isEmpty());
        assertEquals(1, f.cancelled);
    }

    @Test public void queuedUiResultIsRemovedDiscardedAndCannotDeliverAgain() {
        Fixture f = new Fixture();
        f.start(() -> "resource");
        f.work.remove(0).run();
        Runnable stale = f.main.get(0);
        f.destroy();
        stale.run();
        assertTrue(f.main.isEmpty());
        assertTrue(f.results.isEmpty());
        assertEquals(List.of("resource"), f.discarded);
    }

    @Test public void operationFailureReachesUiAndReleasesCallbacks() throws Exception {
        Fixture f = new Fixture();
        UiRequest<String> request = f.start(() -> { throw new IOException("Disconnected"); });
        f.drain();
        assertEquals("Disconnected", f.errors.get(0).getMessage());
        assertEquals(0, f.lifecycle.getObserverCount());
        for (String name : List.of("success", "failure", "cancel", "result", "error")) {
            var field = UiRequest.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(request));
        }
    }

    @Test public void executorRejectionReportsFailureInsteadOfStrandingUi() {
        Fixture f = new Fixture();
        new UiRequest<>(f, () -> "unused", f.results::add, f.errors::add, () -> {}, f.discarded::add,
                task -> { throw new RejectedExecutionException("Full"); }, f.main::add, f.main::remove);
        f.drain();
        assertEquals(1, f.errors.size());
        assertEquals(0, f.lifecycle.getObserverCount());
    }

    @Test public void executorCancellationReportsFailureInsteadOfStrandingUi() {
        Fixture f = new Fixture();
        new UiRequest<>(f, () -> "unused", f.results::add, f.errors::add, () -> {}, f.discarded::add,
                task -> ((java.util.concurrent.Future<?>) task).cancel(false), f.main::add, f.main::remove);
        f.drain();
        assertEquals(1, f.errors.size());
        assertEquals(0, f.lifecycle.getObserverCount());
    }

    @Test public void lateResultAfterIgnoredInterruptionIsDiscarded() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        f.start(() -> {
            started.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { }
            }
            return "late";
        });
        Thread worker = new Thread(f.work.remove(0));
        worker.start();
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            f.destroy();
        } finally {
            release.countDown();
            worker.join(3000);
        }
        assertFalse(worker.isAlive());
        assertTrue(f.results.isEmpty());
        assertTrue(f.main.isEmpty());
        assertEquals(List.of("late"), f.discarded);
    }

    private static class Fixture implements LifecycleOwner {
        final LifecycleRegistry lifecycle = new LifecycleRegistry(this);
        final List<Runnable> work = new ArrayList<>(), main = new ArrayList<>();
        final List<String> results = new ArrayList<>(), discarded = new ArrayList<>();
        final List<Exception> errors = new ArrayList<>();
        int cancelled;
        Fixture() { lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE); }
        @Override public Lifecycle getLifecycle() { return lifecycle; }
        void destroy() { lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY); }
        UiRequest<String> start(Callable<String> operation) {
            return new UiRequest<>(this, operation, results::add, errors::add, () -> cancelled++, discarded::add,
                    work::add, main::add, main::remove);
        }
        void drain() {
            while (!work.isEmpty()) work.remove(0).run();
            while (!main.isEmpty()) main.remove(0).run();
        }
    }
}
