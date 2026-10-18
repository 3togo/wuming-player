package com.wuming.android.tv.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteAccessTest {

    @Test
    public void protectsAdministrativeAndPrivateRoutes() {
        assertTrue(RemoteAccess.isProtected("/action?do=control"));
        assertTrue(RemoteAccess.isProtected("/desktop/v1/search"));
        assertTrue(RemoteAccess.isProtected("/cache?do=get"));
        assertTrue(RemoteAccess.isProtected("/media"));
        assertTrue(RemoteAccess.isProtected("/file/"));
        assertTrue(RemoteAccess.isProtected("/upload"));
        assertTrue(RemoteAccess.isProtected("/packages/inspect"));
        assertTrue(RemoteAccess.isProtected("/packages/files/id/version/file"));
        assertTrue(RemoteAccess.isProtected("/newFolder"));
        assertTrue(RemoteAccess.isProtected("/delFolder"));
        assertTrue(RemoteAccess.isProtected("/delFile"));
    }

    @Test
    public void leavesDiscoveryAndPlaybackDeliveryCompatible() {
        assertFalse(RemoteAccess.isProtected("/device"));
        assertFalse(RemoteAccess.isProtected("/proxy?do=media"));
        assertFalse(RemoteAccess.isProtected("/m3u8?key=session"));
    }

    @Test
    public void excludesCredentialsFromBackupPreferences() {
        assertTrue(RemoteAccess.isSecretPreference("remote_server_token"));
        assertTrue(RemoteAccess.isSecretPreference("remote_paired_tokens"));
        assertFalse(RemoteAccess.isSecretPreference("theme"));
    }
}
