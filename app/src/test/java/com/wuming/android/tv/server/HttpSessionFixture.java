package com.wuming.android.tv.server;

import java.lang.reflect.Proxy;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;

/** A non-null session for handlers whose response depends only on the supplied URL. */
public final class HttpSessionFixture {
    private HttpSessionFixture() { }

    public static IHTTPSession unused() {
        return (IHTTPSession) Proxy.newProxyInstance(IHTTPSession.class.getClassLoader(),
                new Class<?>[]{IHTTPSession.class}, (proxy, method, args) -> {
                    throw new AssertionError("This handler should not inspect the session: " + method.getName());
                });
    }
}
