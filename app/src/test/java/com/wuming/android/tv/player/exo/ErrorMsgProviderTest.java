package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;
import androidx.media3.common.PlaybackException;
import com.wuming.tv.core.cloud.OpenDriveFailure;
import java.io.IOException;

public class ErrorMsgProviderTest {

    private static final class PlayerFailure extends PlaybackException {
        PlayerFailure(Throwable cause) {
            // The explicit timestamp avoids Android's clock in this JVM-only fixture.
            super("fixture", cause, ERROR_CODE_IO_UNSPECIFIED, null, 0L);
        }
    }

    @Test
    public void cloudFailureSurvivesTheMediaPlayerErrorBoundary() {
        for (OpenDriveFailure.Reason reason : OpenDriveFailure.Reason.values()) {
            OpenDriveFailure failure = new OpenDriveFailure("quark", reason);
            PlaybackException player = new PlayerFailure(new IOException("refresh wrapper", failure));
            assertEquals(failure.getMessage(), new ErrorMsgProvider().get(player));
        }
    }

    @Test
    public void acceptsCaseInsensitiveOpenDriveErrorHeader() {
        assertEquals("Re-login to UC or use another source.",
                ErrorMsgProvider.providerMessage(Map.of(
                        "x-opendrive-error", List.of("Re-login to UC or use another source."))));
    }

    @Test
    public void rejectsInjectedOpenDriveErrorHeader() {
        assertEquals("", ErrorMsgProvider.providerMessage(Map.of(
                "X-OpenDrive-Error", List.of("Safe\r\nInjected: bad"))));
    }
}
