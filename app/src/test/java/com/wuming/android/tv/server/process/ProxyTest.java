package com.wuming.android.tv.server.process;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Map;

public class ProxyTest {

    @Test
    public void fixedLengthIsParsedCaseInsensitively() {
        assertEquals(4096L, Proxy.contentLength(Map.of("content-length", "4096")));
        assertEquals(5L * 1024L * 1024L * 1024L,
                Proxy.contentLength(Map.of("Content-Length", "5368709120")));
        assertEquals(-1L, Proxy.contentLength(Map.of("Content-Length", "invalid")));
        assertEquals(-1L, Proxy.contentLength(Map.of()));
    }
}
