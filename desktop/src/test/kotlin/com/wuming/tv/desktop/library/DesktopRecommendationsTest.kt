package com.wuming.tv.desktop.library

import com.wuming.tv.core.catalog.CatalogEpisode
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogLine
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class DesktopRecommendationsTest {
    private lateinit var directory: java.nio.file.Path
    private lateinit var path: java.nio.file.Path
    // Fixed wall time keeps persisted recommendation records deterministic.
    private val clock = Clock.fixed(Instant.ofEpochMilli(TimeUnit.MINUTES.toMillis(40)), ZoneOffset.UTC)

    @Before
    fun createDirectory() {
        directory = Files.createTempDirectory("tvbox-rec-test-")
        path = directory.resolve("library.json")
    }

    @After
    fun removeDirectory() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun `desktop ranking uses the shared core policy over recorded viewings`() {
        val library = DesktopUserLibrary.open(path, clock)
        val watched = CatalogItem("site-a", "vod-1", "Watched", "", "", "2020")
        library.recordPlayback(
            watched,
            CatalogLine("l", episodes = listOf(CatalogEpisode("E1", "e1"))),
            CatalogEpisode("E1", "e1"),
        )
        library.recordViewing(watched, null, TimeUnit.MINUTES.toMillis(40), completed = false)

        val match = CatalogItem("site-a", "vod-2", "Match", "", "", "2020")
        val different = CatalogItem("site-a", "vod-3", "Different", "", "", "1995")
        val ranked = library.recommendations().rank("site-a", listOf(different, match)).map { it.id }

        // The learned 2020 preference must lift the matching decade candidate first.
        assertEquals(listOf("vod-2", "vod-3"), ranked)
    }

    @Test
    fun `no viewing signal preserves input order`() {
        val library = DesktopUserLibrary.open(path, clock)
        val first = CatalogItem("site-a", "vod-2", "A", "", "", "2020")
        val second = CatalogItem("site-a", "vod-3", "B", "", "", "2020")
        val ranked = library.recommendations().rank("site-a", listOf(first, second)).map { it.id }

        assertEquals(listOf("vod-2", "vod-3"), ranked)
    }

    @Test
    fun `opening playback does not mistake wall time for watched duration`() {
        val library = DesktopUserLibrary.open(path, clock)
        val watched = CatalogItem("site-a", "vod-1", "Watched", "", "", "2020")
        val line = CatalogLine("l", episodes = listOf(CatalogEpisode("E1", "e1")))
        library.recordPlayback(watched, line, line.episodes.single())
        val original = listOf(
            CatalogItem("site-a", "old", "Old", "", "", "1995"),
            CatalogItem("site-a", "match", "Match", "", "", "2020"),
        )

        assertEquals(original, library.recommendations().rank("site-a", original))
    }

    @Test
    fun `separate sessions for the same title are retained independently`() {
        val store = DesktopViewingRecordStore(directory.resolve("viewing.json"), maxRecords = 2)
        val item = CatalogItem("site-a", "vod-1", "Watched")
        val first = DesktopViewingRecord.fromPlayback(item, null, 60_000, 1, false)
        val second = DesktopViewingRecord.fromPlayback(item, null, 120_000, 2, true)

        store.save(first)
        store.save(second)

        assertEquals(2, store.records().size)
        assertNotEquals(store.records()[0].id, store.records()[1].id)
        assertThrows(IllegalArgumentException::class.java) {
            DesktopViewingRecordStore(directory.resolve("invalid.json"), maxRecords = 0)
        }
    }
}
