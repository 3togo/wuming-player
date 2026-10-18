package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Map;

public class Pan123OpenDriveProviderTest {

    @Test
    public void recognizesEveryDomainInThePinnedCatVodContract() {
        assertEquals("https://www.123pan.com/s/Abcdef12",
                Pan123OpenDriveProvider.canonicalShareUrl(
                        "https://www.123592.com/s/Abcdef12"));
        assertEquals("https://www.123pan.com/s/Abcdef12?pwd=9xYz",
                Pan123OpenDriveProvider.canonicalShareUrl(
                        "https://123912.com/s/Abcdef12?pwd=9xYz"));
        assertEquals("https://www.123pan.com/s/Abcdef12",
                Pan123OpenDriveProvider.canonicalShareUrl(
                        "https://www.123865.com/s/Abcdef12.html"));
        assertNull(Pan123OpenDriveProvider.canonicalShareUrl(
                "https://www.123592.com.evil.example/s/Abcdef12"));
    }

    @Test
    public void shareDownloadUsesThePinnedCatVodAndroidHeaders() {
        Map<String, String> headers =
                Pan123OpenDriveProvider.shareDownloadHeaders("reviewed-token");

        assertEquals("Bearer reviewed-token", headers.get("Authorization"));
        assertEquals("android", headers.get("platform"));
        assertEquals("application/json", headers.get("Content-Type"));
        assertEquals(Pan123OpenDriveProvider.shareHeaders().get("User-Agent"),
                headers.get("User-Agent"));
        assertFalse(headers.containsKey("App-Version"));
    }
}
