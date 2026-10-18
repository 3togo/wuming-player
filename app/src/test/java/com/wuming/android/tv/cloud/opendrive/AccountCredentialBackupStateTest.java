package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Guards the scope of the encrypted account archive.
 *
 * <p>Bilibili is not a cloud drive, but its session is the one account the settings
 * backup cannot carry, so it has to stay in this domain list. Dropping it silently
 * breaks login restore after a reinstall.
 */
public class AccountCredentialBackupStateTest {

    @Test
    public void bilibiliSessionIsPartOfTheAccountArchive() {
        assertTrue(AccountCredentialBackupState.DOMAINS.contains("bilibili.com"));
    }
}
