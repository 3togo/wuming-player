package com.wuming.android.tv.player.exo;

import androidx.media3.datasource.HttpDataSource;
import java.io.IOException;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpPlaybackErrorsTest {
    private HttpDataSource.InvalidResponseCodeException response(int status) {
        // The status classifier never reads DataSpec; avoid Android Uri in this JVM fixture.
        return new HttpDataSource.InvalidResponseCodeException(
                status, "HTTP failure", null, Collections.emptyMap(), null, new byte[0]);
    }

    @Test public void wrappedDeniedRequestDoesNotRetryAndRetainsItsStatus() {
        IOException error = new IOException(new IOException(response(403)));
        assertEquals(Integer.valueOf(403), HttpPlaybackErrors.responseCode(error));
        assertFalse(HttpPlaybackErrors.shouldRetry(error));
    }

    @Test public void transientHttpAndOrdinaryIoFailuresRemainRetryable() {
        assertTrue(HttpPlaybackErrors.shouldRetry(response(503)));
        assertTrue(HttpPlaybackErrors.shouldRetry(new IOException()));
    }
}
