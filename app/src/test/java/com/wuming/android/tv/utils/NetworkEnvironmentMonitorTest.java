package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class NetworkEnvironmentMonitorTest {

    @Test
    public void fingerprintChangesOnlyForMaterialTransferConditions() {
        String wifi = NetworkEnvironmentMonitor.fingerprint(
                "wifi", true, true, false, 60_000);

        assertEquals(wifi, NetworkEnvironmentMonitor.fingerprint(
                "wifi", true, true, false, 62_000));
        assertNotEquals(wifi, NetworkEnvironmentMonitor.fingerprint(
                "wifi", true, true, false, 20_000));
        assertNotEquals(wifi, NetworkEnvironmentMonitor.fingerprint(
                "wifi", true, true, true, 60_000));
    }
}
