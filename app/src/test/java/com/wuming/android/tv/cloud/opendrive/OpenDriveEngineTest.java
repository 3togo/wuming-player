package com.wuming.android.tv.cloud.opendrive;

import com.wuming.tv.core.cloud.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class OpenDriveEngineTest {

    @Test
    public void defaultsExposeSevenBuiltInProviders() {
        OpenDriveEngine engine = new OpenDriveEngine();

        assertEquals(7, engine.providers().size());
        assertEquals("alipan", engine.providers().get(0).id());
        assertEquals("123pan", engine.providers().get(4).id());
        assertEquals("quark", engine.providers().get(1).id());
        assertEquals("tianyi", engine.providers().get(5).id());
        assertEquals("webdav", engine.providers().get(6).id());
        for (int index = 0; index < 6; index++) assertTrue(engine.providers().get(index).available());
        assertFalse(engine.providers().get(6).available());
        assertEquals("Pending — connector implementation is disabled",
                engine.providers().get(6).availabilityMessage());
    }

    @Test
    public void unknownProviderFailsWithoutEchoingUntrustedId() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> new OpenDriveEngine().provider("secret-value"));

        assertEquals("Unknown OpenDrive provider", error.getMessage());
    }

    @Test
    public void expiredProviderSessionIsReportedWithoutDeletingCredentials() {
        ExpiredProvider provider = new ExpiredProvider();
        OpenDriveEngine engine = new OpenDriveEngine(List.of(provider));

        OpenDriveFailure error = assertThrows(
                OpenDriveFailure.class, () -> engine.resolve("expired", "opaque"));
        assertEquals(OpenDriveFailure.Reason.AUTH_REQUIRED, error.reason());
        assertEquals(401, error.httpStatus());
        assertFalse(provider.loggedOut);
    }

    @Test
    public void forbiddenShareDoesNotBecomeExpiredAuthenticationOrTriggerRetry() {
        ExpiredProvider provider = new ExpiredProvider();
        provider.status = 403;
        OpenDriveFailure error = assertThrows(OpenDriveFailure.class,
                () -> OpenDriveFailures.resolve(provider, "opaque"));
        assertEquals(OpenDriveFailure.Reason.ACCESS_DENIED, error.reason());
        assertEquals(1, provider.calls);
        assertFalse(provider.loggedOut);
    }

    private static final class ExpiredProvider implements OpenDriveProvider {
        private boolean loggedOut;
        private int status = 401;
        private int calls;

        @Override public String id() { return "expired"; }
        @Override public String displayName() { return "Expired Drive"; }
        @Override public boolean available() { return true; }
        @Override public String availabilityMessage() { return ""; }
        @Override public boolean authenticated() { return true; }
        @Override public OpenDriveLoginChallenge beginLogin() { return null; }
        @Override public OpenDriveLoginState pollLogin(String sessionId) { return OpenDriveLoginState.FAILED; }
        @Override public List<OpenDriveItem> list(String parentId) { return Collections.emptyList(); }
        @Override public OpenDrivePlayback resolve(String itemId) throws Exception {
            calls++;
            throw new OpenDriveHttp.StatusException(status);
        }
        @Override public void logout() { loggedOut = true; }
    }
}
