package com.wuming.quickjs.utils;

import com.whl.quickjs.wrapper.JSArray;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.Assert.*;

public class JSUtilTest {
    private static final class Wrapper {
        final List<Object> values = new ArrayList<>();
        final List<Object> keys = new ArrayList<>();
        final Thread owner = Thread.currentThread();
        int releases;
        Throwable failure;
        Throwable cleanupFailure;
        final JSArray object = (JSArray) Proxy.newProxyInstance(JSArray.class.getClassLoader(),
                new Class<?>[]{JSArray.class}, (proxy, method, args) -> {
                    assertSame(owner, Thread.currentThread());
                    if (method.getName().equals("release")) {
                        releases++;
                        if (cleanupFailure != null) throw cleanupFailure;
                        return null;
                    }
                    if (method.getName().equals("set") || method.getName().equals("setProperty")) {
                        if (values.size() == 1 && failure != null) throw failure;
                        boolean array = method.getName().equals("set");
                        values.add(args[array ? 0 : 1]);
                        keys.add(args[array ? 1 : 0]);
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
    }

    @Test public void bytesKeepSignedValuesAndTransferOwnership() {
        Wrapper w = new Wrapper();
        assertSame(w.object, JSUtil.fillArray(w.object, new byte[]{-128, -1, 0, 127}));
        assertEquals(List.of(-128, -1, 0, 127), w.values);
        assertEquals(List.of(0, 1, 2, 3), w.keys);
        assertEquals(0, w.releases);
    }

    @Test public void stringsAndMapPreserveNullsAndOrder() {
        Wrapper strings = new Wrapper();
        JSUtil.fillArray(strings.object, Arrays.asList("中", null, ""));
        assertEquals(Arrays.asList("中", null, ""), strings.values);
        Wrapper map = new Wrapper();
        Map<String, String> input = new LinkedHashMap<>();
        input.put("first", null); input.put("second", "value");
        assertSame(map.object, JSUtil.fillObject(map.object, input));
        assertEquals(List.of("first", "second"), map.keys);
        assertEquals(Arrays.asList(null, "value"), map.values);
        assertEquals(0, strings.releases + map.releases);
    }

    @Test public void emptyConversionsTransferUnreleasedWrappers() {
        Wrapper w = new Wrapper();
        assertSame(w.object, JSUtil.fillArray(w.object, (byte[]) null));
        assertSame(w.object, JSUtil.fillArray(w.object, Collections.emptyList()));
        assertSame(w.object, JSUtil.fillObject(w.object, null));
        assertTrue(w.values.isEmpty()); assertEquals(0, w.releases);
    }

    @Test public void everyPartialConversionReleasesOnceAndRethrowsOriginal() {
        for (int type = 0; type < 3; type++) {
            Wrapper w = new Wrapper();
            w.failure = new IllegalStateException("conversion");
            int selected = type;
            assertSame(w.failure, assertThrows(IllegalStateException.class, () -> {
                if (selected == 0) JSUtil.fillArray(w.object, new byte[]{1, 2});
                if (selected == 1) JSUtil.fillArray(w.object, List.of("a", "b"));
                if (selected == 2) JSUtil.fillObject(w.object, Map.of("a", "b", "c", "d"));
            }));
            assertEquals(1, w.releases); assertEquals(1, w.values.size());
        }
    }

    @Test public void cleanupFailureDoesNotHideConversionError() {
        Wrapper w = new Wrapper();
        w.failure = new AssertionError("conversion");
        w.cleanupFailure = new IllegalStateException("cleanup");
        assertSame(w.failure, assertThrows(AssertionError.class,
                () -> JSUtil.fillArray(w.object, new byte[]{1, 2})));
        assertArrayEquals(new Throwable[]{w.cleanupFailure}, w.failure.getSuppressed());
        assertEquals(1, w.releases);
    }
}
