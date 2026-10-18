package com.wuming.tv.desktop.live
import com.wuming.tv.core.live.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.prefs.Preferences

class DesktopLiveLibraryStoreTest {
    @Test fun `favorites and selection survive opening a new port`() {
        val preferences = Preferences.userRoot().node("wuming-test/" + UUID.randomUUID())
        try {
            val first = DesktopLiveLibraryStore(preferences)
            first.setFavorite("source", "channel", true)
            first.saveSelection("source", LiveSelection("channel", "route"))
            val reopened = DesktopLiveLibraryStore(preferences)
            assertTrue(reopened.favorite("source", "channel"))
            assertEquals(LiveSelection("channel", "route"), reopened.selection("source"))
            reopened.setFavorite("source", "channel", false)
            assertFalse(first.favorite("source", "channel"))
        } finally { preferences.removeNode(); preferences.flush() }
    }
}
