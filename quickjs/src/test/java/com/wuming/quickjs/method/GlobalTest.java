package com.wuming.quickjs.method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.whl.quickjs.wrapper.JSFunction;
import com.wuming.quickjs.bean.Req;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public class GlobalTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Global global = new Global(null, executor);

    @After
    public void cleanup() throws Exception {
        global.cancelPending();
        executor.submit(global::destroy).get(2, TimeUnit.SECONDS);
        executor.shutdownNow();
    }

    @Test
    public void javascriptReflectionKeepsBoxedParameterTypes() throws Exception {
        assertEquals(Integer.class, Global.class.getMethod("getPort").getReturnType());
        assertTrue(Global.class.getMethod("getProxy", Boolean.class)
                .isAnnotationPresent(com.whl.quickjs.wrapper.JSMethod.class));
        assertTrue(Global.class.getMethod("js2Proxy", Boolean.class, Integer.class,
                String.class, String.class, com.whl.quickjs.wrapper.JSObject.class)
                .isAnnotationPresent(com.whl.quickjs.wrapper.JSMethod.class));
        assertTrue(Global.class.getMethod("setTimeout", JSFunction.class, Integer.class)
                .isAnnotationPresent(com.whl.quickjs.wrapper.JSMethod.class));
    }

    @Test
    public void completedTimersReleaseHeldFunctions() throws Exception {
        AtomicInteger holds = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(1);
        JSFunction function = function(holds, releases, called);
        executor.submit(() -> global.setTimeout(function, 0)).get(2, TimeUnit.SECONDS);
        assertTrue(called.await(2, TimeUnit.SECONDS));
        executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
        assertEquals(1, holds.get());
        assertEquals(1, releases.get());
    }

    @Test
    public void destroyingRuntimeCancelsAndReleasesLongTimersExactlyOnce() throws Exception {
        AtomicInteger holds = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(1);
        JSFunction function = function(holds, releases, called);
        executor.submit(() -> global.setTimeout(function, 60000)).get(2, TimeUnit.SECONDS);
        global.cancelPending();
        executor.submit(global::destroy).get(2, TimeUnit.SECONDS);
        executor.submit(global::destroy).get(2, TimeUnit.SECONDS);
        assertEquals(1, called.getCount());
        assertEquals(1, holds.get());
        assertEquals(1, releases.get());
    }

    @Test
    public void closedRuntimeDoesNotRetainNewTimers() throws Exception {
        AtomicInteger holds = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch called = new CountDownLatch(1);
        global.cancelPending();
        executor.submit(() -> global.setTimeout(function(holds, releases, called), 0)).get(2, TimeUnit.SECONDS);
        assertEquals(0, holds.get());
        assertEquals(0, releases.get());
        assertEquals(1, called.getCount());
    }

    @Test
    public void responseArrivingAfterCancellationIsClosedWithoutDispatch() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        global.cancelPending();
        callback().onResponse(new okhttp3.OkHttpClient().newCall(new Request.Builder().url("http://localhost/fixture").build()), response(closed));
        assertTrue(closed.get());
    }

    @Test
    public void cancellationClosesResponseWaitingForRuntimeThread() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        executor.execute(() -> {
            blocked.countDown();
            try { resume.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(2, TimeUnit.SECONDS));
        AtomicBoolean closed = new AtomicBoolean();
        try {
            callback().onResponse(new okhttp3.OkHttpClient().newCall(new Request.Builder().url("http://localhost/fixture").build()), response(closed));
            global.cancelPending();
            assertTrue(closed.get());
        } finally {
            resume.countDown();
        }
        executor.submit(() -> {}).get(2, TimeUnit.SECONDS);
    }

    private Callback callback() throws Exception {
        Method method = Global.class.getDeclaredMethod("getCallback", long.class, Req.class);
        method.setAccessible(true);
        return (Callback) method.invoke(global, 1L, new Req());
    }

    private static Response response(AtomicBoolean closed) {
        ResponseBody body = new ResponseBody() {
            final BufferedSource source = Okio.buffer(new ForwardingSource(new Buffer().writeUtf8("fixture")) {
                @Override public void close() throws java.io.IOException {
                    closed.set(true);
                    super.close();
                }
            });
            @Override public MediaType contentType() { return MediaType.get("text/plain"); }
            @Override public long contentLength() { return 7; }
            @Override public BufferedSource source() { return source; }
        };
        return new Response.Builder().request(new Request.Builder().url("http://localhost/").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build();
    }

    private static JSFunction function(AtomicInteger holds, AtomicInteger releases, CountDownLatch called) {
        return (JSFunction) Proxy.newProxyInstance(JSFunction.class.getClassLoader(),
                new Class<?>[]{JSFunction.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "hold": holds.incrementAndGet(); break;
                        case "release": releases.incrementAndGet(); break;
                        case "callVoid": called.countDown(); break;
                        default: throw new AssertionError(method.getName());
                    }
                    return null;
                });
    }
}
