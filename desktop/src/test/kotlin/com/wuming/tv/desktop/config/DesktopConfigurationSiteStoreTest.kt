package com.wuming.tv.desktop.config

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class DesktopConfigurationSiteStoreTest {
    private lateinit var preferences: Preferences

    @Before
    fun createNode() {
        preferences = Preferences.userRoot().node("tvbox-configuration-site-test/${UUID.randomUUID()}")
    }

    @After
    fun removeNode() {
        preferences.removeNode()
        preferences.flush()
    }

    @Test
    fun `site selection survives reorder because its stable key is stored`() {
        val store = DesktopConfigurationSiteStore(preferences)
        assertNull(store.rememberedSiteKey())

        store.rememberSiteKey("first-site-key")

        assertEquals(
            "first-site-key",
            DesktopConfigurationSiteStore(preferences).rememberedSiteKey(),
        )
    }
}
