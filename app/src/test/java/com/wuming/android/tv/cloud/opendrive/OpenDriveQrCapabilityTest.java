package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenDriveQrCapabilityTest {

    @Test
    public void advertisesOnlyProvidersWithApiBackedQrSessions() {
        assertTrue(new AliPanOpenDriveProvider().supportsQrLogin());
        assertTrue(new QuarkOpenDriveProvider().supportsQrLogin());
        assertTrue(new UcOpenDriveProvider().supportsQrLogin());
        assertTrue(new BaiduOpenDriveProvider().supportsQrLogin());

        assertFalse(new Pan123OpenDriveProvider().supportsQrLogin());
        assertFalse(new TianyiOpenDriveProvider().supportsQrLogin());
        assertFalse(new WebDavOpenDriveProvider().supportsQrLogin());
    }
}
