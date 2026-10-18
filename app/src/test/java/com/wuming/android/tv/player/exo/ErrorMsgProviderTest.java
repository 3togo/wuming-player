package com.wuming.android.tv.player.exo;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ErrorMsgProviderTest {

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
