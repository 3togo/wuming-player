package com.wuming.android.tv.ui.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.R;
import com.wuming.tv.core.cloud.OpenDriveAccountSnapshot;
import com.wuming.tv.core.cloud.OpenDriveTokenState;

import org.junit.Test;

public class OpenDriveAccountAdapterTest {

    @Test
    public void savedCredentialsUseConnectedColorAndHideAutoLogin() {
        OpenDriveAccountSnapshot account = account(true, OpenDriveTokenState.VERIFIED);

        assertEquals(R.color.cloud_login_connected,
                OpenDriveAccountAdapter.providerTintResource(account));
        assertFalse(OpenDriveAccountAdapter.showAssistedLogin(account));
    }

    @Test
    public void failedCredentialVerificationUsesDisconnectedColor() {
        OpenDriveAccountSnapshot account = account(true, OpenDriveTokenState.ERROR);

        assertEquals(R.color.cloud_login_disconnected,
                OpenDriveAccountAdapter.providerTintResource(account));
        assertFalse(OpenDriveAccountAdapter.showAssistedLogin(account));
    }

    @Test
    public void signedOutAccountKeepsLoginColorAndShowsAutoLogin() {
        OpenDriveAccountSnapshot account = account(false, OpenDriveTokenState.SIGNED_OUT);

        assertEquals(R.color.cloud_login_disconnected,
                OpenDriveAccountAdapter.providerTintResource(account));
        assertTrue(OpenDriveAccountAdapter.showAssistedLogin(account));
    }

    private static OpenDriveAccountSnapshot account(boolean tokenPresent,
                                                     OpenDriveTokenState state) {
        return new OpenDriveAccountSnapshot(
                "provider", "Provider", true, true, tokenPresent, state);
    }
}
