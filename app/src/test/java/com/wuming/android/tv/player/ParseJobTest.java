package com.wuming.android.tv.player;

import static org.junit.Assert.*;

import com.wuming.android.tv.impl.ParseCallback;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ParseJobTest {

    @Test
    public void completionShutsDownWorkersBeforeCallingTheObserver() {
        try (Harness h = new Harness()) {
            AtomicInteger callbacks = new AtomicInteger();
            h.create(new Callback() {
                @Override public void onParseSuccess(Map<String, String> headers, String url, String from) {
                    assertTrue(h.worker.isShutdown());
                    assertTrue(h.candidates.isShutdown());
                    callbacks.incrementAndGet();
                }
            });
            h.job.onParseSuccess(Map.of(), "https://example.test/a", "");
            h.job.onParseError();
            h.job.onParseSuccess(Map.of(), "https://example.test/b", "");
            assertEquals(1, h.queue.size());
            h.queue.remove(0).run();
            assertEquals(1, callbacks.get());
        }
    }

    @Test
    public void throwingObserverCannotLeakParserWorkers() {
        try (Harness h = new Harness()) {
            RuntimeException failure = new IllegalStateException("observer failed");
            h.create(new Callback() {
                @Override public void onParseError() { throw failure; }
            });
            h.job.onParseError();
            assertSame(failure, assertThrows(RuntimeException.class, h.queue.remove(0)::run));
            assertTrue(h.worker.isShutdown());
            assertTrue(h.candidates.isShutdown());
        }
    }

    @Test
    public void stoppingBeforeQueuedDeliverySuppressesTheStaleResult() {
        try (Harness h = new Harness()) {
            AtomicInteger callbacks = new AtomicInteger();
            h.create(new Callback() {
                @Override public void onParseError() { callbacks.incrementAndGet(); }
            });
            h.job.onParseError();
            h.job.stop();
            h.queue.remove(0).run();
            assertEquals(0, callbacks.get());
        }
    }

    @Test
    public void shortMediaUrlsAreAcceptedAndLongErrorMessagesAreRejected() {
        assertTrue(ParseJob.hasAbsoluteUrl("https://a.test/v"));
        assertTrue(ParseJob.hasAbsoluteUrl(" rtsp://host/live "));
        assertTrue(ParseJob.hasAbsoluteUrl("file:///video.mp4"));
        assertTrue(ParseJob.hasAbsoluteUrl("https://a.test/one|||1000***https://a.test/two|||2000"));
        assertFalse(ParseJob.hasAbsoluteUrl("This is an error message longer than forty characters"));
        assertFalse(ParseJob.hasAbsoluteUrl("https:/missing-host"));
        assertFalse(ParseJob.hasAbsoluteUrl(null));
        assertFalse(ParseJob.hasAbsoluteUrl(" "));
    }

    @Test
    public void parserHeadersNormalizeUaAndUseTheWinningCandidatesFallback() {
        Map<String, String> headers = ParseJob.getHeader(JsonParser.parseString(
                "{\"ua\":\"agent\",\"referer\":\"origin\",\"Cookie\":{\"bad\":true}}")
                .getAsJsonObject(), Map.of("Cookie", "fallback"));
        assertEquals(Map.of("User-Agent", "agent", "Referer", "origin"), headers);
        Map<String, String> fallback = Map.of("Cookie", "candidate");
        Map<String, String> copied = ParseJob.getHeader(
                JsonParser.parseString("{}").getAsJsonObject(), fallback);
        assertEquals(fallback, copied);
        copied.clear();
        assertEquals(1, fallback.size());
    }

    private static class Callback implements ParseCallback {
        @Override public void onParseSuccess(Map<String, String> headers, String url, String from) {}
        @Override public void onParseError() {}
    }

    private static final class Harness implements AutoCloseable {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final ExecutorService candidates = Executors.newFixedThreadPool(2);
        final List<Runnable> queue = new ArrayList<>();
        ParseJob job;

        void create(ParseCallback callback) {
            job = new ParseJob(callback, queue::add, worker, candidates);
        }

        @Override public void close() {
            if (job != null) job.stop();
            worker.shutdownNow();
            candidates.shutdownNow();
        }
    }
}
