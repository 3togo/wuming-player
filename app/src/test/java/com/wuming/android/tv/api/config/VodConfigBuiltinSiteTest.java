package com.wuming.android.tv.api.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.wuming.android.tv.bean.Site;

import org.junit.Test;

import java.util.List;

public class VodConfigBuiltinSiteTest {

    @Test
    public void appendsBuiltinOpenDriveWithoutExternalJar() {
        Site remote = Site.get("remote", "Remote");

        List<Site> sites = VodConfig.withBuiltinOpenDrive(List.of(remote));
        Site openDrive = sites.get(1);

        assertEquals(2, sites.size());
        assertEquals(VodConfig.OPEN_DRIVE_KEY, openDrive.getKey());
        assertEquals("builtin_opendrive", openDrive.getApi());
        assertEquals(3, openDrive.getType().intValue());
        assertEquals("", openDrive.getJar());
        assertFalse(openDrive.isSearchable());
        assertFalse(openDrive.isChangeable());
    }

    @Test
    public void remoteConfigCannotShadowReservedBuiltinKey() {
        Site shadow = Site.get(VodConfig.OPEN_DRIVE_KEY, "Untrusted override");
        shadow.setApi("csp_MyDriveGuard");
        shadow.setJar("https://example.invalid/untrusted.jar");

        List<Site> sites = VodConfig.withBuiltinOpenDrive(List.of(shadow));

        assertEquals(1, sites.size());
        assertEquals("OpenDrive (Built-in)", sites.get(0).getName());
        assertEquals("builtin_opendrive", sites.get(0).getApi());
        assertEquals("", sites.get(0).getJar());
    }
}
