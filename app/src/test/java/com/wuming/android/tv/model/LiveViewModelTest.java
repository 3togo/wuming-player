package com.wuming.android.tv.model;

import static org.junit.Assert.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import org.junit.Rule;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class LiveViewModelTest {
    @Rule public InstantTaskExecutorRule rule = new InstantTaskExecutorRule();

    @Test public void completionReleasesFutureAndItsResult() throws Exception {
        LiveViewModel model = new LiveViewModel();
        CountDownLatch delivered = new CountDownLatch(1);
        try {
            execute(model, Object::new, value -> delivered.countDown(), error -> fail(error.toString()));
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            synchronized (model) {
                assertTrue(futures(model).isEmpty());
            }
        } finally {
            model.onCleared();
        }
    }

    @Test public void failureAlsoReleasesFuture() throws Exception {
        LiveViewModel model = new LiveViewModel();
        CountDownLatch delivered = new CountDownLatch(1);
        try {
            execute(model, () -> { throw new IllegalStateException("fixture"); },
                    value -> fail("Unexpected success"), error -> delivered.countDown());
            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            synchronized (model) {
                assertTrue(futures(model).isEmpty());
            }
        } finally {
            model.onCleared();
        }
    }

    @Test public void teardownInterruptsRequestAndRejectsFurtherWork() throws Exception {
        LiveViewModel model = new LiveViewModel();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        try {
            execute(model, () -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException expected) {
                    interrupted.countDown();
                }
                return new Object();
            }, value -> delivered.incrementAndGet(), error -> delivered.incrementAndGet());
            assertTrue(started.await(2, TimeUnit.SECONDS));
            model.onCleared();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            execute(model, () -> calls.incrementAndGet(), value -> delivered.incrementAndGet(),
                    error -> delivered.incrementAndGet());
            synchronized (model) {
                assertTrue(futures(model).isEmpty());
            }
            assertEquals(0, calls.get());
            assertEquals(0, delivered.get());
        } finally {
            release.countDown();
            model.onCleared();
        }
    }

    // Exercise request ownership with controllable providers, without live HTTP or Android APIs.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void execute(LiveViewModel model, Callable<?> work,
                                Consumer<Object> success, Consumer<Throwable> failure) throws Exception {
        Class<? extends Enum> type = (Class<? extends Enum>) Class.forName(
                LiveViewModel.class.getName() + "$TaskType");
        Method method = LiveViewModel.class.getDeclaredMethod("execute", type,
                Callable.class, Consumer.class, Consumer.class);
        method.setAccessible(true);
        method.invoke(model, Enum.valueOf(type, "URL"), work, success, failure);
    }

    private static Map<?, ?> futures(LiveViewModel model) throws Exception {
        Field field = LiveViewModel.class.getDeclaredField("futures");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(model);
    }
}
