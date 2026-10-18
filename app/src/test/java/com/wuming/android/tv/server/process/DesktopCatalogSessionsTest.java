package com.wuming.android.tv.server.process;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DesktopCatalogSessionsTest {

    @After
    public void tearDown() {
        DesktopCatalogSessions.clear();
    }

    @Test
    public void itemHandlesHideProviderIdentifiersAndBindToSource() {
        String providerId = "/detail/path?token=private";
        String handle = DesktopCatalogSessions.issueItem("source-a", providerId);

        assertTrue(handle.matches("i_[0-9a-f]{48}"));
        assertNotEquals(providerId, handle);
        assertEquals(providerId, DesktopCatalogSessions.resolveItem("source-a", handle));
        assertNull(DesktopCatalogSessions.resolveItem("source-b", handle));
    }

    @Test
    public void episodeHandlesBindToSourceAndPlaybackFlag() {
        String providerId = "{\"accountToken\":\"private\"}";
        String handle = DesktopCatalogSessions.issueEpisode("source-a", "line-a", providerId);

        assertTrue(handle.matches("e_[0-9a-f]{48}"));
        assertNotEquals(providerId, handle);
        assertEquals(providerId, DesktopCatalogSessions.resolveEpisode("source-a", "line-a", handle));
        assertNull(DesktopCatalogSessions.resolveEpisode("source-a", "line-b", handle));
        assertNull(DesktopCatalogSessions.resolveEpisode("source-b", "line-a", handle));
    }
}
