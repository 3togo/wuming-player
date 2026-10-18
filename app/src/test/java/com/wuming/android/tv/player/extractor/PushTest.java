package com.wuming.android.tv.player.extractor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PushTest {

    @Test
    public void targetExtractsValidPushUrl() {
        assertEquals("https://example.com/video.m3u8", Push.target("push://https://example.com/video.m3u8"));
    }

    @Test
    public void targetRejectsMalformedInputWithoutThrowing() {
        assertEquals("", Push.target(null));
        assertEquals("", Push.target(""));
        assertEquals("", Push.target("push:"));
        assertEquals("", Push.target("push://"));
        assertEquals("", Push.target("https://example.com/video.m3u8"));
    }
}
