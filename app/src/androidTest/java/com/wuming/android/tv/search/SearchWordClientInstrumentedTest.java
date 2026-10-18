package com.wuming.android.tv.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import fi.iki.elonen.NanoHTTPD;

@RunWith(AndroidJUnit4.class)
public class SearchWordClientInstrumentedTest {

    private FixtureServer server;
    private SearchWordClient client;

    @Before
    public void setUp() throws IOException {
        server = new FixtureServer();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        String base = "http://127.0.0.1:" + server.getListeningPort();
        client = new SearchWordClient(base + "/hot", base + "/secondary?time=", base + "/suggest?key=");
    }

    @After
    public void tearDown() {
        client.cancel();
        server.stop();
    }

    @Test
    public void newerSuggestionCancelsAndSuppressesLateCallback() throws Exception {
        AtomicBoolean staleCalled = new AtomicBoolean();
        AtomicReference<String> latest = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);

        client.requestSuggestion("slow", result -> staleCalled.set(true));
        client.requestSuggestion("latest", result -> {
            latest.set(result);
            completed.countDown();
        });

        assertTrue(completed.await(15, TimeUnit.SECONDS));
        Thread.sleep(350);
        assertFalse(staleCalled.get());
        assertEquals("latest", latest.get());
    }

    @Test
    public void explicitCancellationSuppressesCallback() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        client.requestSuggestion("slow", result -> called.set(true));
        client.cancel();
        assertEquals(0, client.inFlightCount());

        Thread.sleep(400);
        assertFalse(called.get());
    }

    @Test
    public void simultaneousHotRequestsDeliverIndependentResponses() throws Exception {
        CountDownLatch completed = new CountDownLatch(2);
        AtomicReference<String> primary = new AtomicReference<>();
        AtomicReference<String> secondary = new AtomicReference<>();
        SearchWordClient.ResultCallback primaryCallback = result -> {
            primary.set(result);
            completed.countDown();
        };
        SearchWordClient.ResultCallback secondaryCallback = result -> {
            secondary.set(result);
            completed.countDown();
        };

        client.requestHot(primaryCallback, secondaryCallback);

        assertTrue(completed.await(20, TimeUnit.SECONDS));
        assertEquals("hot", primary.get());
        assertEquals("secondary", secondary.get());
    }

    @Test
    public void truncatedResponseIsRemovedFromInFlightCalls() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        client.requestSuggestion("broken", result -> called.set(true));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (client.inFlightCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }

        assertEquals(0, client.inFlightCount());
        assertFalse(called.get());
    }

    private static final class FixtureServer extends NanoHTTPD {
        FixtureServer() { super(0); }

        @Override
        public Response serve(IHTTPSession session) {
            if (session.getUri().equals("/hot")) return newFixedLengthResponse("hot");
            if (session.getUri().equals("/secondary")) return newFixedLengthResponse("secondary");
            if (session.getUri().equals("/suggest")) {
                String keyword = session.getParameters().getOrDefault("key", java.util.List.of("")).get(0);
                if (keyword.equals("broken")) {
                    return newFixedLengthResponse(Response.Status.OK, "text/plain",
                            new ByteArrayInputStream(new byte[0]), 10L);
                }
                if (keyword.equals("slow")) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                    }
                }
                return newFixedLengthResponse(keyword);
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing");
        }
    }
}
