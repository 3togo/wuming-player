package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class OpenDriveTokenManagerTest {

    @Test
    public void tokenPresenceIsNotReportedAsLoggedInUntilProviderVerifiesIt() {
        FakeProvider provider = new FakeProvider();
        provider.present = true;
        OpenDriveTokenManager manager = manager(provider);

        assertEquals(OpenDriveTokenState.PRESENT, manager.snapshot("fake").state());
        assertFalse(manager.snapshot("fake").verified());

        assertEquals(OpenDriveTokenState.VERIFIED, manager.verify("fake").state());
        assertTrue(manager.snapshot("fake").verified());
    }

    @Test
    public void rejectedTokenBecomesInvalidAndEnablesFreshQrLogin() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.present = true;
        provider.valid = false;
        OpenDriveTokenManager manager = manager(provider);

        OpenDriveAccountSnapshot invalid = manager.verify("fake");

        assertEquals(OpenDriveTokenState.INVALID, invalid.state());
        assertTrue(invalid.canStartLogin());
        OpenDriveLoginChallenge challenge = manager.beginLogin("fake");
        assertEquals("session", challenge.id());
        assertTrue(provider.loggedOut);
    }

    @Test
    public void transportFailureDoesNotDeleteOrMisreportSavedToken() {
        FakeProvider provider = new FakeProvider();
        provider.present = true;
        provider.verificationError = true;
        OpenDriveTokenManager manager = manager(provider);

        OpenDriveAccountSnapshot result = manager.verify("fake");

        assertEquals(OpenDriveTokenState.ERROR, result.state());
        assertTrue(result.tokenPresent());
        assertFalse(result.verified());
        assertFalse(result.canStartLogin());
        assertFalse(provider.loggedOut);
    }

    @Test
    public void qrConfirmationMustPassTheSameProviderVerification() throws Exception {
        FakeProvider provider = new FakeProvider();
        OpenDriveTokenManager manager = manager(provider);
        OpenDriveLoginChallenge challenge = manager.beginLogin("fake");
        // Rendering the hub while a provider window is polling must be read-only.
        assertEquals(OpenDriveTokenState.SIGNED_OUT, manager.snapshot("fake").state());
        provider.present = true;

        assertEquals(OpenDriveLoginState.CONFIRMED,
                manager.pollLogin("fake", challenge.id()));
        assertEquals(OpenDriveTokenState.VERIFIED, manager.snapshot("fake").state());
        assertEquals(OpenDriveLoginState.EXPIRED,
                manager.pollLogin("fake", challenge.id()));
    }

    @Test
    public void resetClearsTokenAndAllCachedVerdicts() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.present = true;
        OpenDriveTokenManager manager = manager(provider);
        assertEquals(OpenDriveTokenState.VERIFIED, manager.verify("fake").state());

        manager.reset("fake");

        assertTrue(provider.loggedOut);
        assertEquals(OpenDriveTokenState.SIGNED_OUT, manager.snapshot("fake").state());
    }

    @Test
    public void savedUnverifiedTokenCannotBeOverwrittenByQrSession() {
        FakeProvider provider = new FakeProvider();
        provider.present = true;
        OpenDriveTokenManager manager = manager(provider);

        assertThrows(IllegalStateException.class, () -> manager.beginLogin("fake"));
    }

    @Test
    public void restoreInvalidatesPreviousVerdictAndReloadsProviderState() {
        FakeProvider provider = new FakeProvider();
        provider.present = true;
        OpenDriveTokenManager manager = manager(provider);
        assertEquals(OpenDriveTokenState.VERIFIED, manager.verify("fake").state());
        manager.credentialsRestored();
        assertEquals(OpenDriveTokenState.PRESENT, manager.snapshot("fake").state());
        assertTrue(provider.restored);
        assertFalse(provider.loggedOut);
        provider.valid = false;
        assertEquals(OpenDriveTokenState.INVALID, manager.verify("fake").state());
    }

    @Test
    public void restoreExpiresPendingQrLogin() throws Exception {
        FakeProvider provider = new FakeProvider();
        OpenDriveTokenManager manager = manager(provider);
        OpenDriveLoginChallenge challenge = manager.beginLogin("fake");
        manager.credentialsRestored();
        assertEquals(OpenDriveLoginState.EXPIRED, manager.pollLogin("fake", challenge.id()));
    }

    @Test
    public void rejectedRestoreRetainsPreviousVerification() {
        FakeProvider provider = new FakeProvider();
        provider.present = true;
        OpenDriveTokenManager manager = manager(provider);
        manager.verify("fake");
        assertThrows(IllegalArgumentException.class, () -> manager.replaceCredentials(() -> {
            throw new IllegalArgumentException("Invalid backup");
        }));
        assertEquals(OpenDriveTokenState.VERIFIED, manager.snapshot("fake").state());
        assertFalse(provider.restored);
    }

    @Test
    public void successfulReplacementInvalidatesVerdictAfterMutation() {
        FakeProvider provider = new FakeProvider();
        provider.present = true;
        OpenDriveTokenManager manager = manager(provider);
        manager.verify("fake");
        manager.replaceCredentials(() -> {
            assertFalse(provider.restored);
            provider.valid = false;
        });
        assertTrue(provider.restored);
        assertEquals(OpenDriveTokenState.PRESENT, manager.snapshot("fake").state());
        assertEquals(OpenDriveTokenState.INVALID, manager.verify("fake").state());
    }

    private static OpenDriveTokenManager manager(FakeProvider provider) {
        return new OpenDriveTokenManager(new OpenDriveEngine(List.of(provider)));
    }

    private static final class FakeProvider implements OpenDriveProvider {
        boolean present;
        boolean valid = true;
        boolean verificationError;
        boolean loggedOut;
        boolean restored;

        @Override public void credentialsRestored() { restored = true; }

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake Drive"; }
        @Override public boolean available() { return true; }
        @Override public String availabilityMessage() { return ""; }
        @Override public boolean authenticated() { return present; }
        @Override public boolean supportsQrLogin() { return true; }
        @Override public boolean verifyAuthentication() throws Exception {
            if (verificationError) throw new IOException("offline");
            return present && valid;
        }
        @Override public OpenDriveLoginChallenge beginLogin() {
            return new OpenDriveLoginChallenge("session", "https://login.test/qr", 0);
        }
        @Override public OpenDriveLoginState pollLogin(String sessionId) {
            return present ? OpenDriveLoginState.CONFIRMED : OpenDriveLoginState.WAITING;
        }
        @Override public List<OpenDriveItem> list(String parentId) { return Collections.emptyList(); }
        @Override public OpenDrivePlayback resolve(String itemId) { return null; }
        @Override public void logout() {
            loggedOut = true;
            present = false;
        }
    }
}
