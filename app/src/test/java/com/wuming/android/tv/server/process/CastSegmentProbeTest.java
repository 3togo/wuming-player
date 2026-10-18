package com.wuming.android.tv.server.process;

import org.junit.Test;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import okhttp3.*;
import okio.Okio;
import static org.junit.Assert.*;

public class CastSegmentProbeTest {
    @Test public void headSizesStayOrderedWithoutReadingBodies() throws Exception {
        List<Fixture> fixtures = new CopyOnWriteArrayList<>();
        long[] sizes = CastSegmentProbe.sizes(20, (index, method, range) -> {
            assertEquals("HEAD", method); assertNull(range);
            Fixture fixture = new Fixture(200, index + 1, null); fixtures.add(fixture); return fixture.call();
        });
        for (int i = 0; i < sizes.length; i++) assertEquals(i + 1, sizes[i]);
        assertEquals(20, fixtures.size());
        assertTrue(fixtures.stream().allMatch(f -> f.closed.get()));
    }

    @Test public void unsupportedZeroAndFailedHeadFallBackToOneByteRange() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Fixture head = new Fixture(mode == 0 ? 405 : 200, 0, null);
            if (mode == 2) head.failure = true;
            Fixture range = new Fixture(206, 1, "bytes 0-0/12345");
            AtomicInteger requests = new AtomicInteger();
            long[] sizes = CastSegmentProbe.sizes(1, (index, method, header) -> {
                requests.incrementAndGet();
                if (method.equals("HEAD")) { assertNull(header); return head.call(); }
                assertEquals("GET", method); assertEquals("bytes=0-0", header); return range.call();
            });
            assertArrayEquals(new long[]{12345}, sizes); assertEquals(2, requests.get());
            assertTrue(range.closed.get());
            if (!head.failure) assertTrue(head.closed.get());
        }
    }

    @Test public void serverIgnoringRangeCanProvideFullLengthWithoutReadingPayload() throws Exception {
        Fixture head = new Fixture(200, 0, null), full = new Fixture(200, 9000000000L, null);
        assertArrayEquals(new long[]{9000000000L}, CastSegmentProbe.sizes(1,
                (index, method, range) -> (method.equals("HEAD") ? head : full).call()));
        assertTrue(head.closed.get()); assertTrue(full.closed.get());
    }

    @Test public void refusesMalformedAndUnrelatedContentRanges() {
        for (String value : Arrays.asList(null, "bytes 0-0/*", "bytes 1-1/42", "bytes 0-8/42", "items 0-0/42",
                "bytes 0-0/0", "bytes 0-0/-1", "bytes 0-0/9223372036854775808", "bytes nonsense/42")) {
            assertNull(value, CastSegmentProbe.rangeTotal(value));
        }
        assertEquals(Long.valueOf(42), CastSegmentProbe.rangeTotal(" BYTES 0-0/42 "));
    }

    @Test public void rangeTrimmingPreservesJavaWhitespaceRules() {
        assertEquals(Long.valueOf(42), CastSegmentProbe.rangeTotal("\u0000\tbytes 0-0/42\r\n"));
        assertNull(CastSegmentProbe.rangeTotal("\u2003bytes 0-0/42\u2003"));
    }

    @Test public void validatesCountAndSumBeforePublishingLengths() throws Exception {
        for (int count : new int[]{0, -1, 10001}) {
            assertThrows(IOException.class, () -> CastSegmentProbe.sizes(count,
                    (i, m, r) -> { fail("Must not call upstream"); return null; }));
        }
        assertThrows(IOException.class, () -> CastSegmentProbe.sizes(2,
                (i, m, r) -> new Fixture(200, Long.MAX_VALUE, null).call()));
    }

    @Test(timeout = 15000) public void unsizableSegmentCancelsSiblingsAndStopsFurtherSegments() throws Exception {
        CountDownLatch started = new CountDownLatch(4), release = new CountDownLatch(1);
        List<Fixture> fixtures = new CopyOnWriteArrayList<>();
        AtomicInteger gets = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try { CastSegmentProbe.sizes(20, (index, method, range) -> {
                if (method.equals("GET")) { gets.incrementAndGet(); return new Fixture(403, 0, null).call(); }
                Fixture fixture = new Fixture(index == 0 ? 405 : 200, 10, null);
                fixture.started = started; fixture.gate = index == 0 ? release : new CountDownLatch(1);
                fixtures.add(fixture); return fixture.call();
            }); } catch (Throwable error) { failure.set(error); }
        });
        caller.start();
        try {
            await(started); release.countDown(); caller.join(5000);
            assertFalse(caller.isAlive()); assertTrue(failure.get() instanceof IOException);
            assertEquals(4, fixtures.size()); assertEquals(1, gets.get());
            assertTrue(fixtures.stream().anyMatch(f -> f.cancelled.get()));
        } finally { release.countDown(); caller.interrupt(); caller.join(5000); for (Fixture f : fixtures) await(f.finished); }
    }

    @Test(timeout = 15000) public void callerInterruptionPreventsRangeFallbackAndCancelsLateResponses() throws Exception {
        CountDownLatch started = new CountDownLatch(4), release = new CountDownLatch(1);
        List<Fixture> fixtures = new CopyOnWriteArrayList<>();
        AtomicInteger gets = new AtomicInteger(); AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try { CastSegmentProbe.sizes(12, (index, method, range) -> {
                if (method.equals("GET")) gets.incrementAndGet();
                Fixture fixture = new Fixture(200, 0, null);
                fixture.started = started; fixture.gate = release; fixture.ignoreCancellation = true;
                fixtures.add(fixture); return fixture.call();
            }); } catch (Throwable error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start();
        try {
            await(started); caller.interrupt(); caller.join(5000);
            assertFalse(caller.isAlive()); assertTrue(failure.get() instanceof InterruptedIOException);
            assertTrue(interrupted.get()); assertTrue(fixtures.stream().allMatch(f -> f.cancelled.get()));
        } finally { release.countDown(); caller.interrupt(); caller.join(5000); for (Fixture f : fixtures) await(f.finished); }
        assertEquals(0, gets.get()); assertEquals(4, fixtures.size());
    }

    @Test(timeout = 15000) public void limitsTwoProbeJobsAndCancellationIsIsolated() throws Exception {
        CountDownLatch started = new CountDownLatch(4), gateA = new CountDownLatch(1), gateB = new CountDownLatch(1);
        List<Fixture> a = new CopyOnWriteArrayList<>(), b = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failedA = new AtomicReference<>(), failedB = new AtomicReference<>();
        AtomicReference<long[]> resultB = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try { CastSegmentProbe.sizes(2, (i,m,r) -> { Fixture f = new Fixture(200, 7, null);
                f.started = started; f.gate = gateA; a.add(f); return f.call(); }); }
            catch (Throwable error) { failedA.set(error); }
        });
        Thread second = new Thread(() -> {
            try { resultB.set(CastSegmentProbe.sizes(2, (i,m,r) -> { Fixture f = new Fixture(200, 9, null);
                f.started = started; f.gate = gateB; b.add(f); return f.call(); })); }
            catch (Throwable error) { failedB.set(error); }
        });
        first.start(); second.start();
        try {
            await(started);
            assertThrows(IOException.class, () -> CastSegmentProbe.sizes(1, (i,m,r) -> { fail("At capacity"); return null; }));
            first.interrupt(); first.join(5000);
            assertTrue(failedA.get() instanceof InterruptedIOException);
            assertTrue(b.stream().noneMatch(f -> f.cancelled.get()));
            gateB.countDown(); second.join(5000); assertFalse(second.isAlive()); assertNull(failedB.get());
            assertArrayEquals(new long[]{9,9}, resultB.get());
        } finally {
            gateA.countDown(); gateB.countDown(); first.interrupt(); second.interrupt(); first.join(5000); second.join(5000);
            for (Fixture f : a) await(f.finished); for (Fixture f : b) await(f.finished);
        }
    }

    private static void await(CountDownLatch latch) throws Exception { assertTrue(latch.await(5, TimeUnit.SECONDS)); }

    private static final class Fixture {
        final int code; final long length; final String contentRange;
        final AtomicBoolean cancelled = new AtomicBoolean(), closed = new AtomicBoolean();
        final CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch started, gate;
        boolean failure, ignoreCancellation;
        Fixture(int code, long length, String contentRange) { this.code = code; this.length = length; this.contentRange = contentRange; }
        Call call() {
            return (Call) Proxy.newProxyInstance(Call.class.getClassLoader(), new Class<?>[]{Call.class}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "cancel" -> { cancelled.set(true); if (gate != null && !ignoreCancellation) gate.countDown(); yield null; }
                    case "execute" -> {
                        try {
                            if (started != null) started.countDown();
                            if (gate != null) gate.await(10, TimeUnit.SECONDS);
                            if (failure || (cancelled.get() && !ignoreCancellation)) throw new IOException("fixture failure");
                            InputStream input = new InputStream() {
                                @Override public int read() { throw new AssertionError("Probe must not consume payload"); }
                                @Override public void close() { closed.set(true); finished.countDown(); }
                            };
                            Response.Builder response = new Response.Builder().request(new Request.Builder().url("https://fixture.invalid").build())
                                    .protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                                    .body(ResponseBody.create(Okio.buffer(Okio.source(input)), null, length));
                            if (contentRange != null) response.header("Content-Range", contentRange);
                            yield response.build();
                        } catch (Exception error) { finished.countDown(); throw error; }
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
        }
    }
}
