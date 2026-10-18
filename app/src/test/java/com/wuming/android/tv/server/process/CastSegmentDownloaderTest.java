package com.wuming.android.tv.server.process;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.*;
import okio.Okio;
import okio.Timeout;
import static org.junit.Assert.*;

public class CastSegmentDownloaderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void completionOrderDoesNotChangePlaylistByteOrder() throws Exception {
        TestCall[] calls = {new TestCall(2, 2, (byte) 1), new TestCall(3, 3, (byte) 2), new TestCall(4, 4, (byte) 3)};
        calls[0].body.gate = calls[2].body.finished;
        File result = CastSegmentDownloader.download(temp.getRoot(), new long[]{2, 3, 4}, 9, index -> calls[index]);
        assertArrayEquals(new byte[]{1, 1, 2, 2, 2, 3, 3, 3, 3}, Files.readAllBytes(result.toPath()));
        awaitClosed(Arrays.asList(calls));
    }

    @Test public void stagesLargeGeneratedBodiesInOrderWithFourBoundedWorkers() throws Exception {
        int segments = 16, partSize = 1024 * 1024;
        long[] sizes = new long[segments];
        Arrays.fill(sizes, partSize);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger(), largestRead = new AtomicInteger();
        List<TestCall> calls = new CopyOnWriteArrayList<>();
        File result = CastSegmentDownloader.download(temp.getRoot(), sizes, 16L * partSize, index -> {
            TestCall call = new TestCall(partSize, -1, (byte) index);
            call.body.active = active;
            call.body.peak = peak;
            call.body.largestRead = largestRead;
            calls.add(call);
            return call;
        });
        assertEquals(16L * partSize, result.length());
        assertTrue(peak.get() <= CastSegmentDownloader.WORKERS);
        assertTrue(largestRead.get() <= CastSegmentDownloader.BUFFER_BYTES);
        try (InputStream in = new FileInputStream(result)) {
            byte[] buffer = new byte[8192];
            for (int segment = 0; segment < segments; segment++) {
                for (int remaining = partSize; remaining > 0;) {
                    int count = in.read(buffer, 0, Math.min(buffer.length, remaining));
                    assertTrue(count > 0);
                    for (int i = 0; i < count; i++) assertEquals((byte) segment, buffer[i]);
                    remaining -= count;
                }
            }
            assertEquals(-1, in.read());
        }
        assertEquals(segments, calls.size());
        assertTrue(calls.stream().allMatch(call -> call.body.closed.get()));
        assertEquals(0, active.get());
    }

    @Test public void validatesBudgetsBeforeCreatingFilesOrCalls() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        File unrelated = temp.newFile("keep.txt");
        Files.writeString(unrelated.toPath(), "keep");
        for (long[] sizes : List.of(new long[]{5, 6}, new long[]{-1}, new long[]{0}, new long[]{Long.MAX_VALUE, 1})) {
            assertThrows(IOException.class, () -> CastSegmentDownloader.download(temp.getRoot(), sizes, 10,
                    index -> { calls.incrementAndGet(); return new TestCall(1, 1, (byte) 0); }));
        }
        assertEquals(0, calls.get());
        assertEquals(List.of("keep.txt"), Arrays.asList(temp.getRoot().list()));
        assertEquals("keep", Files.readString(unrelated.toPath()));
    }

    @Test public void rejectsActualShortAndLongBodiesEvenWithoutLengthHeader() throws Exception {
        for (int length : new int[]{9, 11}) {
            TestCall call = new TestCall(length, -1, (byte) 1);
            assertThrows(IOException.class, () -> CastSegmentDownloader.download(temp.getRoot(), new long[]{10}, 10, index -> call));
            assertTrue(call.body.closed.get());
            assertEquals(0, temp.getRoot().list().length);
        }
    }

    @Test public void rejectsChangedContentLengthAndHttpErrors() throws Exception {
        TestCall changed = new TestCall(10, 11, (byte) 0);
        TestCall denied = new TestCall(10, 10, (byte) 0);
        denied.code = 403;
        for (TestCall call : List.of(changed, denied)) {
            assertThrows(IOException.class, () -> CastSegmentDownloader.download(temp.getRoot(), new long[]{10}, 10, index -> call));
            assertTrue(call.body.closed.get());
            assertEquals(0, temp.getRoot().list().length);
        }
    }

    @Test public void partialReadFailureRemovesOnlyOwnedStagingFile() throws Exception {
        File unrelated = temp.newFile("keep.ts");
        TestCall call = new TestCall(100000, -1, (byte) 4);
        call.body.failAfter = 20000;
        assertThrows(IOException.class, () -> CastSegmentDownloader.download(temp.getRoot(), new long[]{100000}, 100000, index -> call));
        assertTrue(call.body.closed.get());
        assertEquals(List.of(unrelated.getName()), Arrays.asList(temp.getRoot().list()));
    }

    @Test public void failureCancelsOtherActiveCallsAndStopsNewSegments() throws Exception {
        CountDownLatch started = new CountDownLatch(4), gate = new CountDownLatch(1);
        List<TestCall> calls = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        long[] sizes = new long[20]; Arrays.fill(sizes, 10);
        Thread caller = new Thread(() -> {
            try {
                CastSegmentDownloader.download(temp.getRoot(), sizes, 200, index -> {
                    TestCall call = new TestCall(10, -1, (byte) index);
                    call.body.started = started;
                    call.body.gate = index == 0 ? gate : new CountDownLatch(1);
                    if (index == 0) call.body.failAfter = 0;
                    calls.add(call);
                    return call;
                });
            } catch (Throwable error) { failure.set(error); }
        });
        caller.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            gate.countDown();
            caller.join(5000);
            assertFalse(caller.isAlive());
            assertTrue(failure.get() instanceof IOException);
            assertEquals(4, calls.size());
            assertTrue(calls.stream().anyMatch(call -> call.cancelled));
            awaitClosed(calls);
            assertEquals(0, temp.getRoot().list().length);
        } finally { gate.countDown(); caller.interrupt(); caller.join(5000); }
    }

    @Test public void interruptedCallerCancelsCallsAndLateWorkersCannotRecreateFile() throws Exception {
        CountDownLatch started = new CountDownLatch(4), release = new CountDownLatch(1);
        List<TestCall> calls = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try {
                CastSegmentDownloader.download(temp.getRoot(), new long[]{10, 10, 10, 10}, 40, index -> {
                    TestCall call = new TestCall(10, -1, (byte) index);
                    call.body.started = started;
                    call.body.gate = release;
                    call.body.ignoreCancellation = true;
                    calls.add(call);
                    return call;
                });
            } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        caller.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(5000);
            assertFalse(caller.isAlive());
            assertTrue(failure.get() instanceof InterruptedIOException);
            assertTrue(interrupted.get());
            assertTrue(calls.stream().allMatch(call -> call.cancelled));
            assertEquals(0, temp.getRoot().list().length);
        } finally {
            release.countDown();
            caller.interrupt(); caller.join(5000);
            awaitClosed(calls);
        }
        assertEquals(0, temp.getRoot().list().length);
    }

    @Test(timeout = 10000) public void twoWorkerPreparationLeavesCapacityForAnotherAsset() throws Exception {
        CountDownLatch started = new CountDownLatch(2), release = new CountDownLatch(1);
        TestCall[] slow = new TestCall[4];
        for (int i = 0; i < slow.length; i++) {
            slow[i] = new TestCall(1, 1, (byte) 1);
            slow[i].body.started = started;
            slow[i].body.gate = release;
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try { CastSegmentDownloader.download(temp.getRoot(), new long[]{1, 1, 1, 1}, 4, 2, i -> slow[i]); }
            catch (Throwable error) { failure.set(error); }
        });
        first.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            TestCall fast = new TestCall(1, 1, (byte) 7);
            File result = CastSegmentDownloader.download(temp.getRoot(), new long[]{1}, 1, 2, i -> fast);
            assertArrayEquals(new byte[]{7}, Files.readAllBytes(result.toPath()));
            assertEquals(1, release.getCount());
        } finally { release.countDown(); first.join(5000); }
        assertFalse(first.isAlive());
        assertNull(failure.get());
        awaitClosed(Arrays.asList(slow));
    }

    @Test(timeout = 15000) public void lateAssetGetsNextFreeWorkerBeforeEarlierPlaylistFinishes() throws Exception {
        TestCall[] slow = new TestCall[8];
        CountDownLatch started = new CountDownLatch(4);
        AtomicInteger firstCalls = new AtomicInteger();
        for (int i = 0; i < slow.length; i++) {
            slow[i] = new TestCall(1, 1, (byte) i);
            slow[i].body.started = started;
            slow[i].body.gate = new CountDownLatch(1);
        }
        AtomicReference<Throwable> firstFailure = new AtomicReference<>(), secondFailure = new AtomicReference<>();
        AtomicReference<File> secondOutput = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try { CastSegmentDownloader.download(temp.getRoot(), new long[]{1,1,1,1,1,1,1,1}, 8,
                    i -> { firstCalls.incrementAndGet(); return slow[i]; }); }
            catch (Throwable error) { firstFailure.set(error); }
        });
        TestCall fast = new TestCall(1, 1, (byte) 9);
        fast.body.started = new CountDownLatch(1);
        fast.body.gate = new CountDownLatch(1);
        Thread second = new Thread(() -> {
            try { secondOutput.set(CastSegmentDownloader.download(temp.getRoot(), new long[]{1}, 1, i -> fast)); }
            catch (Throwable error) { secondFailure.set(error); }
        });
        first.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(4, firstCalls.get()); // A lone asset borrows all four workers.
            second.start(); waitUntilWaiting(second);
            slow[0].body.gate.countDown();
            assertTrue(fast.body.started.await(5, TimeUnit.SECONDS));
            assertEquals(4, firstCalls.get()); // Next slot went to the late asset, not segment 5.
            fast.body.gate.countDown(); second.join(5000);
            assertFalse(second.isAlive()); assertNull(secondFailure.get());
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(secondOutput.get().toPath()));
            assertTrue(first.isAlive());
        } finally {
            fast.body.gate.countDown();
            for (TestCall call : slow) call.body.gate.countDown();
            first.join(5000); second.join(5000);
        }
        assertFalse(first.isAlive()); assertNull(firstFailure.get());
        awaitClosed(Arrays.asList(slow));
    }

    @Test(timeout = 15000) public void abandonedWorkRetainsCapacityAndQueuedCancellationStartsNoCalls() throws Exception {
        CountDownLatch started = new CountDownLatch(4), release = new CountDownLatch(1);
        List<TestCall> calls = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>(), secondFailure = new AtomicReference<>();
        AtomicInteger queuedCalls = new AtomicInteger();
        Thread first = new Thread(() -> {
            try { CastSegmentDownloader.download(temp.getRoot(), new long[]{1,1,1,1}, 4, i -> {
                TestCall call = new TestCall(1, 1, (byte) 1);
                call.body.started = started; call.body.gate = release; call.body.ignoreCancellation = true;
                calls.add(call); return call;
            }); } catch (Throwable error) { firstFailure.set(error); }
        });
        Thread second = new Thread(() -> {
            try { CastSegmentDownloader.download(temp.getRoot(), new long[]{1}, 1, i -> {
                queuedCalls.incrementAndGet(); return new TestCall(1, 1, (byte) 2);
            }); } catch (Throwable error) { secondFailure.set(error); }
        });
        first.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            second.start(); waitUntilWaiting(second);
            first.interrupt(); first.join(5000);
            assertTrue(firstFailure.get() instanceof InterruptedIOException);
            assertThrows(IOException.class, () -> CastSegmentDownloader.download(temp.getRoot(), new long[]{1}, 1,
                    i -> { fail("Full scheduler must reject before starting calls"); return null; }));
            second.interrupt(); second.join(5000);
            assertTrue(secondFailure.get() instanceof InterruptedIOException);
            assertEquals(0, queuedCalls.get());
            assertEquals(0, temp.getRoot().list().length);
        } finally {
            release.countDown(); first.interrupt(); second.interrupt();
            first.join(5000); second.join(5000); awaitClosed(calls);
        }
    }

    @Test(timeout = 15000) public void cancellingOneAssetLeavesOtherActiveCallsRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(4), firstGate = new CountDownLatch(1), secondGate = new CountDownLatch(1);
        TestCall[] firstCalls = {new TestCall(1, 1, (byte) 1), new TestCall(1, 1, (byte) 1)};
        TestCall[] secondCalls = {new TestCall(1, 1, (byte) 2), new TestCall(1, 1, (byte) 2)};
        for (TestCall call : firstCalls) { call.body.started = started; call.body.gate = firstGate; }
        for (TestCall call : secondCalls) { call.body.started = started; call.body.gate = secondGate; }
        AtomicReference<Throwable> firstFailure = new AtomicReference<>(), secondFailure = new AtomicReference<>();
        AtomicReference<File> output = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try { CastSegmentDownloader.download(temp.getRoot(), new long[]{1,1}, 2, i -> firstCalls[i]); }
            catch (Throwable error) { firstFailure.set(error); }
        });
        Thread second = new Thread(() -> {
            try { output.set(CastSegmentDownloader.download(temp.getRoot(), new long[]{1,1}, 2, i -> secondCalls[i])); }
            catch (Throwable error) { secondFailure.set(error); }
        });
        first.start(); second.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            first.interrupt(); first.join(5000);
            assertTrue(firstFailure.get() instanceof InterruptedIOException);
            assertTrue(Arrays.stream(firstCalls).allMatch(call -> call.cancelled));
            assertTrue(Arrays.stream(secondCalls).noneMatch(call -> call.cancelled));
            assertTrue(second.isAlive());
            secondGate.countDown(); second.join(5000);
            assertFalse(second.isAlive()); assertNull(secondFailure.get());
            assertArrayEquals(new byte[]{2,2}, Files.readAllBytes(output.get().toPath()));
            assertEquals(1, temp.getRoot().list().length);
        } finally {
            firstGate.countDown(); secondGate.countDown(); first.interrupt(); second.interrupt();
            first.join(5000); second.join(5000); awaitClosed(Arrays.asList(firstCalls)); awaitClosed(Arrays.asList(secondCalls));
        }
    }

    private static void waitUntilWaiting(Thread thread) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.yield();
        assertEquals(Thread.State.WAITING, thread.getState());
    }

    private static void awaitClosed(List<TestCall> calls) throws Exception {
        for (TestCall call : calls) assertTrue(call.body.finished.await(5, TimeUnit.SECONDS));
    }

    private static final class Body extends InputStream {
        final long length;
        final byte value;
        final AtomicBoolean closed = new AtomicBoolean();
        final CountDownLatch finished = new CountDownLatch(1);
        volatile boolean cancelled, ignoreCancellation;
        long position, failAfter = Long.MAX_VALUE;
        boolean entered;
        CountDownLatch started, gate;
        AtomicInteger active, peak, largestRead;
        Body(long length, byte value) { this.length = length; this.value = value; }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            if (!entered) {
                entered = true;
                if (started != null) started.countDown();
                if (gate != null) {
                    while (gate.getCount() > 0) {
                        try { gate.await(); } catch (InterruptedException ignored) { }
                        if (cancelled && !ignoreCancellation) throw new IOException("cancelled");
                    }
                }
            }
            if (cancelled && !ignoreCancellation) throw new IOException("cancelled");
            if (position >= failAfter) throw new IOException("fixture read failure");
            if (position == length) return -1;
            if (largestRead != null) largestRead.accumulateAndGet(count, Math::max);
            int size = (int) Math.min(count, length - position);
            Arrays.fill(bytes, offset, offset + size, value);
            position += size;
            return size;
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                if (active != null) active.decrementAndGet();
                finished.countDown();
            }
        }
    }

    private static final class TestCall implements Call {
        final Body body;
        final long advertised;
        volatile boolean cancelled;
        int code = 200;
        TestCall(long length, long advertised, byte value) { body = new Body(length, value); this.advertised = advertised; }
        @Override public Response execute() {
            if (body.active != null) body.peak.accumulateAndGet(body.active.incrementAndGet(), Math::max);
            return new Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .body(ResponseBody.create(Okio.buffer(Okio.source(body)), null, advertised)).build();
        }
        @Override public Request request() { return new Request.Builder().url("https://fixture.test/segment").build(); }
        @Override public void cancel() {
            cancelled = body.cancelled = true;
            if (body.gate != null && !body.ignoreCancellation) body.gate.countDown();
        }
        @Override public boolean isCanceled() { return cancelled; }
        @Override public boolean isExecuted() { return body.entered; }
        @Override public Timeout timeout() { return Timeout.NONE; }
        @Override public void enqueue(Callback callback) { throw new UnsupportedOperationException(); }
        @Override public Call clone() { throw new UnsupportedOperationException(); }
        @Override public <T> T tag(kotlin.reflect.KClass<T> type) { return null; }
        @Override public <T> T tag(Class<? extends T> type) { return null; }
        @Override public <T> T tag(kotlin.reflect.KClass<T> type, kotlin.jvm.functions.Function0<? extends T> compute) { return compute.invoke(); }
        @Override public <T> T tag(Class<T> type, kotlin.jvm.functions.Function0<? extends T> compute) { return compute.invoke(); }
    }
}
