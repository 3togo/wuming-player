package com.wuming.android.tv.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.quickjs.method.Global;
import com.wuming.quickjs.utils.Async;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.junit.Test;
import org.junit.runner.RunWith;

import fi.iki.elonen.NanoHTTPD;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class QuickJsResourcesInstrumentedTest {
    @Test
    public void failedConversionsReleasePartialArraysOnTheirOwningThread() throws Exception {
        QuickJSLoader.init();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        QuickJSContext context = executor.<QuickJSContext>submit(() -> QuickJSContext.create()).get(20, TimeUnit.SECONDS);
        try {
            executor.submit(() -> {
                int before = context.getObjectRecords().size();
                IllegalStateException failure = new IllegalStateException("fixture conversion failure");
                java.util.List<String> input = new java.util.AbstractList<>() {
                    @Override public int size() { return 2; }
                    @Override public String get(int index) {
                        if (index == 1) throw failure;
                        return "already written";
                    }
                };
                for (int i = 0; i < 100; i++) {
                    org.junit.Assert.assertSame(failure, org.junit.Assert.assertThrows(
                            IllegalStateException.class, () -> com.wuming.quickjs.utils.JSUtil.toArray(context, input)));
                }
                assertTrue(context.getObjectRecords().size() <= before);
            }).get(20, TimeUnit.SECONDS);
        } finally {
            executor.submit(context::destroy).get(20, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test(timeout = 120000)
    public void repeatedHttpRepliesReleaseOptionsHeadersAndArrays() throws Exception {
        QuickJSLoader.init();
        NanoHTTPD server = new NanoHTTPD(0) {
            @Override public Response serve(IHTTPSession session) {
                return newFixedLengthResponse("fixture");
            }
        };
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        QuickJSContext context = executor.<QuickJSContext>submit(() -> QuickJSContext.create()).get(20, TimeUnit.SECONDS);
        Global global = executor.submit(() -> Global.create(context, executor)).get(20, TimeUnit.SECONDS);
        try {
            String url = "http://127.0.0.1:" + server.getListeningPort();
            int before = executor.submit(() -> context.getObjectRecords().size()).get(20, TimeUnit.SECONDS);
            for (int i = 0; i < 20; i++) {
                Object length = executor.submit(() -> context.evaluate(
                        "req('" + url + "', {buffer: 1}).content.length")).get(20, TimeUnit.SECONDS);
                assertEquals(7, ((Number) length).intValue());
                android.util.Log.i("QuickJsOwnership", "Synchronous replies completed: " + (i + 1));
            }
            executor.submit(() -> context.evaluate("globalThis.replies = 0; for (let i = 0; i < 20; i++) "
                    + "_http('" + url + "', {buffer: 1, complete: function(r) { if(r.content.length === 7) replies++; }})"))
                    .get(20, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            int replies;
            do {
                Thread.sleep(10);
                replies = executor.submit(() -> ((Number) context.evaluate("replies")).intValue()).get(20, TimeUnit.SECONDS);
            } while (replies < 20 && System.nanoTime() < deadline);
            assertEquals(20, replies);
            android.util.Log.i("QuickJsOwnership", "Asynchronous replies completed: " + replies);
            int after = executor.submit(() -> context.getObjectRecords().size()).get(20, TimeUnit.SECONDS);
            assertTrue("HTTP wrappers grew from " + before + " to " + after, after <= before);
        } finally {
            executor.submit(global::destroy).get(20, TimeUnit.SECONDS);
            executor.submit(context::destroy).get(20, TimeUnit.SECONDS);
            executor.shutdownNow();
            server.stop();
        }
    }

    @Test
    public void repeatedPromisesReleaseTheirNativeWrappers() throws Exception {
        QuickJSLoader.init();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        QuickJSContext context = executor.<QuickJSContext>submit(() -> QuickJSContext.create()).get(20, TimeUnit.SECONDS);
        try {
            JSObject object = executor.submit(() -> (JSObject) context.evaluate(
                    "({ work: function() { return Promise.resolve('done'); } })")).get(20, TimeUnit.SECONDS);
            int before = executor.submit(() -> context.getObjectRecords().size()).get(20, TimeUnit.SECONDS);
            for (int i = 0; i < 100; i++) {
                Object result = executor.submit(() -> Async.run(object, "work")).get(20, TimeUnit.SECONDS)
                        .get(20, TimeUnit.SECONDS);
                assertEquals("done", result);
            }
            int after = executor.submit(() -> context.getObjectRecords().size()).get(20, TimeUnit.SECONDS);
            assertTrue("Native wrappers grew from " + before + " to " + after, after <= before);
            executor.submit(object::release).get(20, TimeUnit.SECONDS);
        } finally {
            executor.submit(context::destroy).get(20, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    @Test
    public void firedAndCanceledTimersReleaseNativeFunctions() throws Exception {
        QuickJSLoader.init();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        QuickJSContext context = executor.<QuickJSContext>submit(() -> QuickJSContext.create()).get(20, TimeUnit.SECONDS);
        Global global = executor.submit(() -> Global.create(context, executor)).get(20, TimeUnit.SECONDS);
        try {
            executor.submit(() -> context.evaluate("globalThis.fired = 0")).get(20, TimeUnit.SECONDS);
            int before = executor.submit(() -> context.getObjectRecords().size()).get(20, TimeUnit.SECONDS);
            executor.submit(() -> context.evaluate(
                    "for (let i = 0; i < 100; i++) setTimeout(() => { fired++; }, 0)"))
                    .get(20, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            int fired;
            do {
                fired = executor.submit(() -> ((Number) context.evaluate("fired")).intValue()).get(20, TimeUnit.SECONDS);
            } while (fired < 100 && System.nanoTime() < deadline);
            assertEquals(100, fired);
            int after = executor.submit(() -> context.getObjectRecords().size()).get(20, TimeUnit.SECONDS);
            assertTrue("Timer wrappers grew from " + before + " to " + after, after <= before);
            executor.submit(() -> context.evaluate(
                    "for (let i = 0; i < 100; i++) setTimeout(() => { fired++; }, 60000)"))
                    .get(20, TimeUnit.SECONDS);
            global.cancelPending();
            executor.submit(global::destroy).get(20, TimeUnit.SECONDS);
            assertTrue(executor.submit(() -> context.getObjectRecords().size()).get(20, TimeUnit.SECONDS) <= before);
        } finally {
            executor.submit(global::destroy).get(20, TimeUnit.SECONDS);
            executor.submit(context::destroy).get(20, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }
}
