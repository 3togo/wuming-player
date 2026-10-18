package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertEquals;

import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.datasource.HttpDataSource;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.Test;

public class PlaybackLoadErrorHandlingTest {
    private long delay(IOException error, int count) {
        return ExoUtil.buildLoadErrorHandlingPolicy().getRetryDelayMsFor(
                new LoadErrorHandlingPolicy.LoadErrorInfo(
                        null, new MediaLoadData(C.DATA_TYPE_MEDIA), error, count));
    }

    @Test public void unsupportedContainerImmediatelyReachesFormatFallback() {
        assertEquals(C.TIME_UNSET, delay(
                ParserException.createForUnsupportedContainerFeature("No extractor can read stream"), 1));
    }

    @Test public void malformedPlaylistDoesNotDownloadSameResponseAgain() {
        assertEquals(C.TIME_UNSET, delay(
                ParserException.createForMalformedManifest("Not an HLS playlist", null), 1));
    }

    @Test public void transientTimeoutRetainsBoundedRetry() {
        assertEquals(1000, delay(new SocketTimeoutException(), 1));
        assertEquals(C.TIME_UNSET, delay(new SocketTimeoutException(), 2));
    }

    @Test public void throttledResponseHonorsRetryAfterAndStopsAtLoaderBudget() {
        IOException throttled = new HttpDataSource.InvalidResponseCodeException(
                429, "Too Many Requests", null,
                java.util.Map.of("retry-after", java.util.List.of("12")), null, new byte[0]);
        assertEquals(12_000L, delay(throttled, 1));
        assertEquals(C.TIME_UNSET, delay(throttled, 4));
        assertEquals("12", HttpPlaybackErrors.retryAfter(throttled));
    }

    @Test public void requestTimeoutAndDeniedRequestFollowTheSharedStatusPolicy() {
        assertEquals(1_000L, delay(httpStatus(408), 1));
        assertEquals(C.TIME_UNSET, delay(httpStatus(403), 1));
    }

    private static IOException httpStatus(int status) {
        return new HttpDataSource.InvalidResponseCodeException(
                status, "test", null, java.util.Map.of(), null, new byte[0]);
    }
}
