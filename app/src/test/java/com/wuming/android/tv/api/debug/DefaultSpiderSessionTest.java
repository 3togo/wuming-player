package com.wuming.android.tv.api.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.github.catvod.crawler.Spider;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultSpiderSessionTest {

    @Test
    public void invokesReloadsAndDestroysSpiders() throws Exception {
        AtomicInteger created = new AtomicInteger();
        AtomicInteger destroyed = new AtomicInteger();
        SpiderFactory factory = () -> new TestSpider(created.incrementAndGet(), destroyed);

        DefaultSpiderSession session = new DefaultSpiderSession(factory);
        SpiderTrace first = session.invoke(SpiderInvocation.home()).get(1, TimeUnit.SECONDS);
        session.reload();
        SpiderTrace second = session.invoke(SpiderInvocation.home()).get(1, TimeUnit.SECONDS);
        session.close();

        assertEquals(SpiderTraceStatus.SUCCESS, first.getStatus());
        assertEquals("home-1", first.getResponse());
        assertEquals("home-2", second.getResponse());
        assertEquals(2, created.get());
        assertEquals(2, destroyed.get());
    }

    @Test
    public void capturesFailureWithoutThrowingAcrossDebugBoundary() throws Exception {
        try (DefaultSpiderSession session = new DefaultSpiderSession(() -> new Spider() {
            @Override public String searchContent(String key, boolean quick, String page) {
                throw new IllegalStateException("token=secret");
            }
        })) {
            SpiderInvocation invocation = new SpiderInvocation(SpiderOperation.SEARCH,
                    List.of("query", "1"), Map.of("authorization", "Bearer secret"),
                    List.of(), false, false);

            SpiderTrace trace = session.invoke(invocation).get(1, TimeUnit.SECONDS);

            assertEquals(SpiderTraceStatus.FAILURE, trace.getStatus());
            assertEquals("IllegalStateException", trace.getErrorCode());
            assertTrue(trace.getErrorMessage().contains("<redacted>"));
            assertEquals("<redacted>", trace.getRequest().get("parameter.authorization"));
        }
    }

    @Test
    public void cancellationCompletesTraceWithoutClosingBorrowedExecutor() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            try (DefaultSpiderSession session = new DefaultSpiderSession(() -> new Spider() {
                @Override public String homeContent(boolean filter) throws Exception {
                    entered.countDown();
                    release.await();
                    return "late result";
                }
            }, executor)) {
                var completion = session.invoke(SpiderInvocation.home());
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                session.cancel();
                assertEquals(SpiderTraceStatus.CANCELLED, completion.get(1, TimeUnit.SECONDS).getStatus());
            }
            assertFalse(executor.isShutdown());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void nullInvocationKeepsExplicitArgumentError() throws Exception {
        try (SpiderSession session = new DefaultSpiderSession(() -> new Spider() {})) {
            assertThrows(IllegalArgumentException.class, () -> session.invoke(null));
        }
    }

    private static final class TestSpider extends Spider {
        private final int id;
        private final AtomicInteger destroyed;
        private TestSpider(int id, AtomicInteger destroyed) {
            this.id = id;
            this.destroyed = destroyed;
        }
        @Override public String homeContent(boolean filter) { return "home-" + id; }
        @Override public void destroy() { destroyed.incrementAndGet(); }
    }
}
