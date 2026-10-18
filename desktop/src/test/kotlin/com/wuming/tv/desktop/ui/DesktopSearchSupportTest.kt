package com.wuming.tv.desktop.ui

import java.util.prefs.Preferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSearchSupportTest {

    @Test
    fun multipleSearchesRemainValidPreferencesXmlAndPreserveOtherSettings() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        try {
            preferences.put("configurationUrl", "https://example.test/tv")
            preferences.put("guardedTrust.saved", "existing-approval")
            val store = DesktopSearchHistoryStore(preferences)
            store.add("First")
            store.add("Second\u001fpart")
            store.add("电影 🎬")

            reloadXml(preferences)

            assertEquals(listOf("电影 🎬", "Second\u001fpart", "First"), DesktopSearchHistoryStore(preferences).recent())
            assertEquals("https://example.test/tv", preferences.get("configurationUrl", ""))
            assertEquals("existing-approval", preferences.get("guardedTrust.saved", ""))
        } finally { preferences.removeNode() }
    }

    @Test
    fun migratesLegacyHistoryAndRemovesItsInvalidXmlValue() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        try {
            preferences.put("search-keyword-history", "First\u001fSecond")
            assertEquals(listOf("First", "Second"), DesktopSearchHistoryStore(preferences).recent())
            assertNull(preferences.get("search-keyword-history", null))
            reloadXml(preferences)
            assertEquals(listOf("First", "Second"), DesktopSearchHistoryStore(preferences).recent())
        } finally { preferences.removeNode() }
    }

    @Test
    fun encodedHistoryStaysWithinThePreferencesValueLimit() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        try {
            val store = DesktopSearchHistoryStore(preferences)
            repeat(50) { store.add("$it" + "a".repeat(1000)) }
            assertTrue(preferences.get("search-keyword-history-json", "").length <= Preferences.MAX_VALUE_LENGTH)
            reloadXml(preferences)
            assertTrue(DesktopSearchHistoryStore(preferences).recent().first().startsWith("49"))
        } finally { preferences.removeNode() }
    }

    private fun reloadXml(preferences: Preferences) {
        val serialized = ByteArrayOutputStream().also(preferences::exportSubtree).toByteArray()
        preferences.clear()
        // Uses Java's actual validating preferences reader, which rejects the old &#31; encoding.
        Preferences.importPreferences(ByteArrayInputStream(serialized))
    }

    @Test
    fun historyIsMostRecentFirstAndDeduplicated() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        try {
            val store = DesktopSearchHistoryStore(preferences)
            store.add("First")
            store.add("Second")
            store.add("First")

            assertEquals(listOf("First", "Second"), store.recent())
            assertEquals(listOf("First"), store.remove("Second"))
        } finally {
            preferences.removeNode()
        }
    }

    @Test
    fun suggestionPayloadAcceptsAndroidTitleAndNameFields() {
        val json = """{"data":[{"title":"Movie"},{"name":"Series"},{"title":"Movie"}]}"""

        assertEquals(listOf("Movie", "Series"), DesktopSearchWordClient.parseWords(json))
        assertEquals(emptyList<String>(), DesktopSearchWordClient.parseWords("not-json"))
    }
}
