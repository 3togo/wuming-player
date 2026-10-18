package com.wuming.tv.desktop.config

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DesktopConfigurationUrlStoreTest {
    private lateinit var preferences: Preferences

    @Before
    fun createNode() {
        preferences = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
    }

    @After
    fun removeNode() {
        preferences.removeNode()
        preferences.flush()
    }

    @Test
    fun `returns the default until a working configuration is remembered`() {
        val store = DesktopConfigurationUrlStore(preferences)

        assertEquals("https://default.example/tv", store.lastWorkingOr("https://default.example/tv"))
        store.rememberWorking("https://working.example/tv")

        assertEquals("https://working.example/tv", DesktopConfigurationUrlStore(preferences).lastWorkingOr("unused"))
    }
}
