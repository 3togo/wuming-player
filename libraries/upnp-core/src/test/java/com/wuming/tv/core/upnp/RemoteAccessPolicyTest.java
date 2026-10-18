package com.wuming.tv.core.upnp;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RemoteAccessPolicyTest {

    @Test
    public void protectsAdministrativeAndPrivateRoutes() {
        assertTrue(RemoteAccessPolicy.isProtected("/action?do=control"));
        assertTrue(RemoteAccessPolicy.isProtected("/desktop/v1/search"));
        assertTrue(RemoteAccessPolicy.isProtected("/cache?do=get"));
        assertTrue(RemoteAccessPolicy.isProtected("/media"));
        assertTrue(RemoteAccessPolicy.isProtected("/file/"));
        assertTrue(RemoteAccessPolicy.isProtected("/upload"));
        assertTrue(RemoteAccessPolicy.isProtected("/packages/inspect"));
        assertTrue(RemoteAccessPolicy.isProtected("/packages/files/id/version/file"));
        assertTrue(RemoteAccessPolicy.isProtected("/newFolder"));
        assertTrue(RemoteAccessPolicy.isProtected("/delFolder"));
        assertTrue(RemoteAccessPolicy.isProtected("/delFile"));
    }

    @Test
    public void leavesDiscoveryAndPlaybackDeliveryCompatible() {
        assertFalse(RemoteAccessPolicy.isProtected("/device"));
        assertFalse(RemoteAccessPolicy.isProtected("/proxy?do=media"));
        assertFalse(RemoteAccessPolicy.isProtected("/m3u8?key=session"));
    }

    @Test
    public void excludesCredentialsFromBackupPreferences() {
        assertTrue(RemoteAccessPolicy.isSecretPreference("remote_server_token"));
        assertTrue(RemoteAccessPolicy.isSecretPreference("remote_paired_tokens"));
        assertFalse(RemoteAccessPolicy.isSecretPreference("theme"));
    }
}
