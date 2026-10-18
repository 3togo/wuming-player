package com.wuming.quickjs.utils;

import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AsyncOwnershipTest {
    @Test public void forwardsEmptyAndMultipleArgumentsWithoutNestingArrays() {
        for (Object[] expected : new Object[][]{new Object[0], {"value", 7, null}}) {
            JSFunction function = (JSFunction) Proxy.newProxyInstance(JSFunction.class.getClassLoader(),
                    new Class<?>[]{JSFunction.class}, (proxy, method, args) -> {
                        if (method.getName().equals("call")) {
                            assertArrayEquals(expected, (Object[]) args[0]);
                            return "done";
                        }
                        if (method.getName().equals("release")) return null;
                        throw new AssertionError(method.getName());
                    });
            JSObject target = (JSObject) Proxy.newProxyInstance(JSObject.class.getClassLoader(),
                    new Class<?>[]{JSObject.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getJSFunction")) return function;
                        throw new AssertionError(method.getName());
                    });
            assertEquals("done", Async.run(target, "work", expected).join());
        }
    }

    @Test public void throwingThenLookupReleasesReturnedObjectAndFunction() {
        verify(true);
    }

    @Test public void plainObjectTransfersOwnershipWithoutReleasingIt() {
        verify(false);
    }

    private void verify(boolean failLookup) {
        Thread owner = Thread.currentThread();
        AtomicInteger objectReleases = new AtomicInteger(), functionReleases = new AtomicInteger();
        IllegalStateException failure = new IllegalStateException("then getter");
        JSObject result = (JSObject) Proxy.newProxyInstance(JSObject.class.getClassLoader(),
                new Class<?>[]{JSObject.class}, (proxy, method, args) -> {
                    assertSame(owner, Thread.currentThread());
                    if (method.getName().equals("release")) { objectReleases.incrementAndGet(); return null; }
                    if (method.getName().equals("getJSFunction")) {
                        assertEquals("then", args[0]);
                        if (failLookup) throw failure;
                        return null;
                    }
                    throw new AssertionError(method.getName());
                });
        JSFunction function = (JSFunction) Proxy.newProxyInstance(JSFunction.class.getClassLoader(),
                new Class<?>[]{JSFunction.class}, (proxy, method, args) -> {
                    assertSame(owner, Thread.currentThread());
                    if (method.getName().equals("call")) return result;
                    if (method.getName().equals("release")) { functionReleases.incrementAndGet(); return null; }
                    throw new AssertionError(method.getName());
                });
        JSObject target = (JSObject) Proxy.newProxyInstance(JSObject.class.getClassLoader(),
                new Class<?>[]{JSObject.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getJSFunction")) return function;
                    throw new AssertionError(method.getName());
                });
        var future = Async.run(target, "work");
        if (failLookup) assertSame(failure, assertThrows(ExecutionException.class, future::get).getCause());
        else assertSame(result, future.join());
        assertEquals(failLookup ? 1 : 0, objectReleases.get());
        assertEquals(1, functionReleases.get());
    }
}
