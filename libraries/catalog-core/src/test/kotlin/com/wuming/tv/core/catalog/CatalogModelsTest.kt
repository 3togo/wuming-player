package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogModelsTest {

    @Test
    fun unknownPageCountRetainsLeanbackLoadMoreSemantics() {
        assertTrue(CatalogPage(page = 7, pageCount = 0).hasNext)
        assertFalse(CatalogPage(page = 3, pageCount = 3).hasNext)
    }

    @Test
    fun defaultsAreSafeForVersionedTransportDecoding() {
        val detail = CatalogDetail()
        val playback = CatalogPlayback()

        assertEquals(emptyList<CatalogLine>(), detail.lines)
        assertEquals("", detail.item.sourceKey)
        assertFalse(playback.supported)
        assertEquals("", playback.url)
    }

    @Test
    fun detailRetainsSummaryPosterWhenProviderOmitsIt() {
        val summary = CatalogItem(
            sourceKey = "site",
            id = "item",
            name = "Summary title",
            picture = "https://images.example/poster.jpg",
            remarks = "Current",
            year = "2026",
        )
        val detail = CatalogDetail(CatalogItem(sourceKey = "site", id = "item", name = "Detail title"))
            .withSummaryFallback(summary)

        assertEquals("Detail title", detail.item.name)
        assertEquals(summary.picture, detail.item.picture)
        assertEquals(summary.remarks, detail.item.remarks)
        assertEquals(summary.year, detail.item.year)
    }

    @Test
    fun desktopPlaybackAcceptsOnlyRelayableUrlsAndRefusesStructuredProviderPayloads() {
        assertEquals(true, CatalogPlaybackPolicy.isHttpRelayCandidate("https://media.example/video.m3u8"))
        assertEquals(false, CatalogPlaybackPolicy.isHttpRelayCandidate("encrypted-provider-token"))
        assertEquals(false, CatalogPlaybackPolicy.isHttpRelayCandidate("file:///private/video.mp4"))
        assertEquals(true, CatalogPlaybackPolicy.requiresInteractiveResolution("{\"shareToken\":\"private\"}"))
        assertEquals(false, CatalogPlaybackPolicy.requiresInteractiveResolution("https://media.example/id"))
    }

    @Test
    fun catalogueConsumersCanUseAnExecutionBackendWithoutKnowingItsPlatform() {
        val gateway: CatalogGateway = object : CatalogGateway {
            override fun sources() = listOf(CatalogSource("demo", "Demo"))
            override fun search(source: String, query: String) =
                listOf(CatalogItem(source, "opaque", query))
            override fun detail(source: String, id: String) =
                CatalogDetail(CatalogItem(source, id, "Detail"))
            override fun play(source: String, flag: String, id: String) =
                CatalogPlayback(true, "https://media.example/video.m3u8")
        }

        assertEquals("Demo", gateway.sources().single().name)
        assertEquals("Movie", gateway.search("demo", "Movie").single().name)
        assertEquals("opaque", gateway.detail("demo", "opaque").item.id)
        assertEquals(true, gateway.play("demo", "main", "episode").supported)
    }

    @Test
    fun localSourceExecutorsRouteThroughTheSameGatewayAsOtherBackends() {
        var cancelled = false
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("local", "Local JS")
            override fun home() = listOf(CatalogItem("local", "home", "Featured"))
            override fun search(query: String) = listOf(CatalogItem("local", "item", query))
            override fun detail(id: String) = CatalogDetail(CatalogItem("local", id, "Detail"))
            override fun play(flag: String, id: String) =
                CatalogPlayback(true, "https://media.example/local.m3u8")
            override fun cancel() { cancelled = true }
        }
        val gateway = ExecutorCatalogGateway(listOf(executor))

        assertEquals("Local JS", gateway.sources().single().name)
        assertEquals("Featured", gateway.home("local").single().name)
        assertEquals("Movie", gateway.search("local", "Movie").single().name)
        assertEquals("item", gateway.detail("local", "item").item.id)
        assertEquals(true, gateway.play("local", "main", "episode").supported)
        gateway.cancel("local")
        assertEquals(true, cancelled)
    }

    @Test
    fun filterSelectionDropsUnknownKeysAndProviderValues() {
        val type = CatalogType(
            id = "movie",
            filters = listOf(CatalogFilter(
                key = "year",
                values = listOf(CatalogFilterValue("2025", "2025"), CatalogFilterValue("2026", "2026")),
            )),
        )

        assertEquals(
            mapOf("year" to "2026"),
            CatalogFilterSelectionPolicy.normalize(type, mapOf("year" to "2026", "unknown" to "value")),
        )
        assertEquals(emptyMap<String, String>(), CatalogFilterSelectionPolicy.normalize(type, mapOf("year" to "2030")))
    }

    @Test
    fun filterSelectionTogglesLikeLeanbackAndRetainsProviderOrdering() {
        val type = CatalogType(
            id = "movie",
            filters = listOf(
                CatalogFilter(
                    key = "area",
                    name = "Area",
                    initialValue = "cn",
                    values = listOf(CatalogFilterValue("All", ""), CatalogFilterValue("China", "cn")),
                ),
                CatalogFilter(
                    key = "year",
                    name = "Year",
                    values = listOf(CatalogFilterValue("2026", "2026")),
                ),
            ),
        )

        val defaults = CatalogFilterSelectionPolicy.defaults(type)
        assertEquals(mapOf("area" to "cn"), defaults)
        assertEquals(emptyMap<String, String>(), CatalogFilterSelectionPolicy.toggle(type, defaults, "area", "cn"))
        val changed = CatalogFilterSelectionPolicy.toggle(type, defaults, "area", "")
        assertEquals(mapOf("area" to ""), changed)
        assertEquals(listOf("area", "year"), CatalogFilterSelectionPolicy.rows(type, changed).map(CatalogFilterRow::key))
        assertEquals("", CatalogFilterSelectionPolicy.rows(type, changed).first().selectedValue)
    }

    @Test
    fun filterSessionKeepsSelectionsIndependentPerProviderType() {
        val area = CatalogFilter(
            key = "area",
            values = listOf(CatalogFilterValue("All", ""), CatalogFilterValue("China", "cn")),
        )
        val movies = CatalogType("movies", filters = listOf(area))
        val series = CatalogType("series", filters = listOf(area))
        val session = CatalogFilterSelectionSession()

        assertEquals(mapOf("area" to "cn"), session.toggle("site", movies, "area", "cn"))
        assertEquals(emptyMap<String, String>(), session.selection("site", series))
        assertEquals(emptyMap<String, String>(), session.selection("other", movies))
        assertEquals(mapOf("area" to "cn"), session.selection("site", movies))
        assertEquals(emptyMap<String, String>(), session.reset("site", movies))
    }

    @Test
    fun configuredCategoriesRestrictAndOrderTypesLikeAndroidSiteApi() {
        val types = listOf(
            CatalogType("movie", "Movies"),
            CatalogType("series", "Series"),
            CatalogType("anime", "Anime"),
        )

        assertEquals(
            listOf("anime", "movie"),
            CatalogTypeSelectionPolicy.select(types, listOf("Anime", "Missing", "Movies")).map(CatalogType::id),
        )
        assertEquals(types, CatalogTypeSelectionPolicy.select(types, listOf("Missing")))
    }
}
