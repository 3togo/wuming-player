package com.wuming.android.tv.media;

import com.wuming.tv.core.media.HistoryPlaybackFallback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OpenDriveHistoryFallbackInstrumentedTest {

    @Test
    public void missingCanonicalResourcePermitsExactlyOneRawRetry() {
        HistoryPlaybackFallback fallback = new HistoryPlaybackFallback();
        fallback.arm("Cloud", "https://example.test/raw.mp4", null);

        HistoryPlaybackFallback.Attempt first = fallback.consume();

        assertNotNull(first);
        assertEquals("Cloud", first.flag());
        assertEquals("https://example.test/raw.mp4", first.rawUrl());
        assertNull(fallback.consume());
    }
}
