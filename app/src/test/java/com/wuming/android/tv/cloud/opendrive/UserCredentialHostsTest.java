package com.wuming.android.tv.cloud.opendrive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM unit tests for the host-matching rules used by {@link UserCredentialStore#findByUrl}. */
public class UserCredentialHostsTest {

    @Test
    public void exactHostMatchesIgnoringPath() {
        assertTrue(UserCredentialHosts.matches("https://api.lmteam.com/v1", "https://api.lmteam.com/danmaku"));
    }

    @Test
    public void subdomainOfBaseHostMatches() {
        assertTrue(UserCredentialHosts.matches("lmteam.com", "https://sub.lmteam.com/x"));
    }

    @Test
    public void requestSubdomainUnderBaseHostMatches() {
        assertTrue(UserCredentialHosts.matches("api.lmteam.com", "https://sub.api.lmteam.com/x"));
    }

    @Test
    public void differentHostDoesNotMatch() {
        assertFalse(UserCredentialHosts.matches("a.com", "https://b.com"));
    }

    @Test
    public void portIsIgnoredForHostMatch() {
        assertTrue(UserCredentialHosts.matches("https://host.com:8080", "https://host.com/path"));
    }

    @Test
    public void schemeIsIgnoredForHostMatch() {
        assertTrue(UserCredentialHosts.matches("http://host.com", "https://host.com/x"));
    }

    @Test
    public void blankBaseUrlNeverMatches() {
        assertFalse(UserCredentialHosts.matches("", "https://host.com"));
        assertFalse(UserCredentialHosts.matches("   ", "https://host.com"));
    }

    @Test
    public void invalidOrNullRequestUrlNeverMatches() {
        assertFalse(UserCredentialHosts.matches("https://host.com", "not-a-url"));
        assertFalse(UserCredentialHosts.matches("https://host.com", null));
    }

    @Test
    public void hostOfExtractsLowercasedHostAndStripsPort() {
        assertEquals("host.com", UserCredentialHosts.hostOf("https://HOST.com:8080/path?q=1"));
    }

    @Test
    public void hostOfToleratesSchemeLessHost() {
        assertEquals("example.com", UserCredentialHosts.hostOf("example.com"));
        assertEquals("example.com", UserCredentialHosts.hostOf("EXAMPLE.com:8080"));
    }

    @Test
    public void hostOfReturnsNullForEmptyOrNull() {
        assertNull(UserCredentialHosts.hostOf(""));
        assertNull(UserCredentialHosts.hostOf(null));
    }
}
