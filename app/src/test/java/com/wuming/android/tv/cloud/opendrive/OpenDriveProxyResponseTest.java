package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Map;

public class OpenDriveProxyResponseTest {

    @Test
    public void progressiveRelayPreservesLengthAboveFourGiB() {
        Map<String, String> headers = OpenDriveProxyResponse.mediaHeaders(
                null, 5L * 1024L * 1024L * 1024L);

        assertEquals(Map.of("Content-Length", "5368709120"), headers);
    }

    @Test
    public void rangedRelayPreservesExact64BitMetadata() {
        Map<String, String> headers = OpenDriveProxyResponse.mediaHeaders(
                "bytes 5368709120-5368713215/6442450944", 4096L);

        assertEquals("bytes 5368709120-5368713215/6442450944",
                headers.get("Content-Range"));
        assertEquals("bytes", headers.get("Accept-Ranges"));
        assertEquals("4096", headers.get("Content-Length"));
    }
}
