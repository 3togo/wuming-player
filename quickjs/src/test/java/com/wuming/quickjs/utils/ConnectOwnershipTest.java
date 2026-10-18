package com.wuming.quickjs.utils;

import com.whl.quickjs.wrapper.JSObject;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ConnectOwnershipTest {
    @Test public void absentHeadersStillBuildARequest() throws Exception {
        // Request construction is independent of Android's global HTTP bootstrap.
        java.lang.reflect.Method builder = Connect.Companion.getClass().getDeclaredMethod(
                "getRequest", String.class, com.wuming.quickjs.bean.Req.class);
        builder.setAccessible(true);
        okhttp3.Request request = (okhttp3.Request) builder.invoke(Connect.Companion,
                "http://127.0.0.1/fixture", com.wuming.quickjs.bean.Req.objectFrom("{\"buffer\":1}"));
        assertEquals("http://127.0.0.1/fixture", request.url().toString());
        assertEquals(0, request.headers().size());
    }

    private static final class Wrapper {
        int releases;
        String failProperty;
        final Map<String, Object> values = new LinkedHashMap<>();
        final RuntimeException failure = new IllegalStateException("fixture");
        final Thread owner = Thread.currentThread();
        final JSObject object = (JSObject) Proxy.newProxyInstance(JSObject.class.getClassLoader(),
                new Class<?>[]{JSObject.class}, (proxy, method, args) -> {
                    assertSame(owner, Thread.currentThread());
                    if (method.getName().equals("release")) { releases++; return null; }
                    if (method.getName().equals("setProperty")) {
                        if (args[0].equals(failProperty)) throw failure;
                        values.put((String) args[0], args[1]); return null;
                    }
                    throw new AssertionError(method.getName());
                });
    }

    @Test public void successfulErrorResponseTransfersParentAndReleasesHeader() {
        Wrapper parent = new Wrapper(), header = new Wrapper();
        AtomicInteger created = new AtomicInteger();
        assertSame(parent.object, Connect.error(() -> created.getAndIncrement() == 0 ? parent.object : header.object));
        assertSame(header.object, parent.values.get("headers"));
        assertEquals("", parent.values.get("content"));
        assertEquals("", parent.values.get("code"));
        assertEquals(0, parent.releases); assertEquals(1, header.releases);
    }

    @Test public void failedHeaderCreationReleasesParent() {
        Wrapper parent = new Wrapper();
        AtomicInteger created = new AtomicInteger();
        assertSame(parent.failure, assertThrows(IllegalStateException.class, () -> Connect.error(() -> {
            if (created.getAndIncrement() == 0) return parent.object;
            throw parent.failure;
        })));
        assertEquals(1, parent.releases);
    }

    @Test public void eachPropertyFailureReleasesBothOwnedWrappersOnce() {
        for (String name : new String[]{"headers", "content", "code"}) {
            Wrapper parent = new Wrapper(), header = new Wrapper();
            parent.failProperty = name;
            AtomicInteger created = new AtomicInteger();
            assertSame(parent.failure, assertThrows(IllegalStateException.class, () ->
                    Connect.error(() -> created.getAndIncrement() == 0 ? parent.object : header.object)));
            assertEquals(1, parent.releases); assertEquals(1, header.releases);
        }
    }
}
