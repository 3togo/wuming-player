package com.wuming.tv.desktop.library

import com.wuming.tv.core.catalog.CatalogEpisode
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogLine
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DesktopUserLibraryTest {
    private lateinit var directory: java.nio.file.Path
    private lateinit var path: java.nio.file.Path
    private val clock = Clock.fixed(Instant.ofEpochMilli(12_345), ZoneOffset.UTC)

    @Before
    fun createDirectory() {
        directory = Files.createTempDirectory("tvbox-user-library-test-")
        path = directory.resolve("library.json")
    }

    @After
    fun removeDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `private sessions and deleted active history cannot write on close or restart`() {
        val library = DesktopUserLibrary.open(path, clock)
        val item = CatalogItem("site", "private", "Private")
        val episode = CatalogEpisode("One", "one")
        val line = CatalogLine("main", episodes = listOf(episode))
        library.privacy.privateViewing = true
        val privateSession = library.privacy.beginSession()
        library.recordPlayback(item, line, episode, privateSession)
        library.savePlaybackPosition(item, 100, 1000, privateSession)
        library.recordViewing(item, null, 100, true, privateSession)
        library.toggleFavorite(item)
        library.privacy.privateViewing = false
        library.recordPlayback(item, line, episode, privateSession)
        assertTrue(DesktopUserLibrary.open(path, clock).recent(setOf("site")).isEmpty())
        assertTrue(library.isFavorite(item))
        val normal = library.privacy.beginSession()
        library.recordPlayback(item, line, episode, normal)
        library.savePlaybackPosition(item, 99, 100, normal)
        library.recordViewing(item, null, 100, false, normal)
        library.deleteHistory(item)
        assertEquals(0L, library.favorites(setOf("site")).single().positionMillis)
        library.recordPlayback(item, line, episode, normal)
        library.recordViewing(item, null, 100, true, normal)
        assertTrue(DesktopUserLibrary.open(path, clock).recent(setOf("site")).isEmpty())
        assertTrue(DesktopViewingRecordStore(path.resolveSibling("viewing-records.json")).records().isEmpty())
        library.recordPlayback(item, line, episode)
        library.clearHistory()
        assertTrue(DesktopUserLibrary.open(path, clock).recent(setOf("site")).isEmpty())
        assertTrue(library.isFavorite(item))
    }

    @Test
    fun `resume survives relaunch but never leaks into a different episode or line`() {
        val item = CatalogItem("site", "series", "Series")
        val first = CatalogEpisode("One", "one")
        val second = CatalogEpisode("Two", "two")
        val line = CatalogLine("main", episodes = listOf(first, second))
        val library = DesktopUserLibrary.open(path, clock)
        library.recordPlayback(item, line, first)
        library.savePlaybackPosition(item, 90_000, 600_000)
        library.recordPlayback(item, line, first)
        val restored = DesktopUserLibrary.open(path, clock)
        assertEquals(90_000L, restored.playbackPosition(item, line, first))
        assertEquals(0L, restored.playbackPosition(item, line, second))
        assertEquals(0L, restored.playbackPosition(item, line.copy(flag = "other"), first))
        restored.recordPlayback(item, line, second)
        assertEquals(0L, DesktopUserLibrary.open(path, clock).playbackPosition(item, line, second))
    }

    @Test
    fun `playback history and episode selection survive restart`() {
        val item = CatalogItem("site-a", "vod-7", "Seven", "poster", "remark", "2026")
        val line = CatalogLine("line-b", "Line B", listOf(CatalogEpisode("Episode 2", "ep-2")))
        DesktopUserLibrary.open(path, clock).recordPlayback(item, line, line.episodes.single())

        val restored = DesktopUserLibrary.open(path, clock).recent(setOf("site-a")).single()
        assertEquals("vod-7", restored.itemId)
        assertEquals("line-b", restored.lineFlag)
        assertEquals("ep-2", restored.episodeId)
        assertEquals(12_345, restored.updatedAtMillis)
    }

    @Test
    fun `favorites persist and remain independent of history`() {
        val item = CatalogItem("site-a", "vod-8", "Eight")
        val library = DesktopUserLibrary.open(path, clock)

        assertTrue(library.toggleFavorite(item))
        assertTrue(DesktopUserLibrary.open(path, clock).isFavorite(item))
        assertEquals(1, DesktopUserLibrary.open(path, clock).favorites(setOf("site-a")).size)
        assertFalse(DesktopUserLibrary.open(path, clock).toggleFavorite(item))
        assertTrue(DesktopUserLibrary.open(path, clock).favorites(setOf("site-a")).isEmpty())
    }

    @Test
    fun `library hides entries belonging to unloaded providers`() {
        val item = CatalogItem("site-a", "vod-9", "Nine")
        val line = CatalogLine("line", episodes = listOf(CatalogEpisode("One", "one")))
        val library = DesktopUserLibrary.open(path, clock)
        library.recordPlayback(item, line, line.episodes.single())

        assertTrue(library.recent(setOf("site-b")).isEmpty())
    }

    @Test
    fun `savePlaybackPosition updates an existing history entry`() {
        val item = CatalogItem("site-a", "vod-10", "Ten")
        val line = CatalogLine("line", episodes = listOf(CatalogEpisode("One", "one")))
        val library = DesktopUserLibrary.open(path, clock)
        library.recordPlayback(item, line, line.episodes.single())

        library.savePlaybackPosition(item, 120_000L, 3_600_000L)

        val restored = DesktopUserLibrary.open(path, clock).find(item)
        assertEquals(120_000L, restored?.positionMillis)
        assertEquals(3_600_000L, restored?.durationMillis)
    }

    @Test
    fun `playbackPosition returns zero for unknown items`() {
        val item = CatalogItem("site-a", "vod-missing", "Missing")
        val library = DesktopUserLibrary.open(path, clock)
        assertEquals(0L, library.playbackPosition(item))
    }

    @Test
    fun `playbackPosition returns saved position after restart`() {
        val item = CatalogItem("site-a", "vod-11", "Eleven")
        val line = CatalogLine("line", episodes = listOf(CatalogEpisode("One", "one")))
        val library = DesktopUserLibrary.open(path, clock)
        library.recordPlayback(item, line, line.episodes.single())
        library.savePlaybackPosition(item, 90_000L, 2_700_000L)

        val restored = DesktopUserLibrary.open(path, clock).playbackPosition(item)
        assertEquals(90_000L, restored)
    }

    @Test
    fun `savePlaybackPosition does nothing for unknown items`() {
        val item = CatalogItem("site-a", "vod-unknown", "Unknown")
        val library = DesktopUserLibrary.open(path, clock)
        library.savePlaybackPosition(item, 50_000L, 100_000L)
        assertEquals(0L, library.playbackPosition(item))
    }
}
