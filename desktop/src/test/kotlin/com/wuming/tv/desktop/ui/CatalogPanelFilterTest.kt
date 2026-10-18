package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.LibraryDetailResolver
import com.wuming.tv.core.catalog.CatalogDetail
import com.wuming.tv.core.catalog.CatalogFilter
import com.wuming.tv.core.catalog.CatalogFilterValue
import com.wuming.tv.core.catalog.CatalogHome
import com.wuming.tv.core.catalog.CatalogItem
import com.wuming.tv.core.catalog.CatalogPage
import com.wuming.tv.core.catalog.CatalogPlayback
import com.wuming.tv.core.catalog.CatalogSource
import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.catalog.ExecutorCatalogGateway
import com.wuming.tv.core.catalog.CatalogType
import java.awt.CardLayout
import java.awt.Component
import java.awt.Container
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPanelFilterTest {

    @Test
    fun supersededPlaybackResolutionDoesNotLaunchAnExtraPlayer() {
        val firstStarted = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val firstFinished = java.util.concurrent.CountDownLatch(1)
        val launches = CopyOnWriteArrayList<List<String>>()
        val item = CatalogItem("provider", "show", "Show")
        val line = com.wuming.tv.core.catalog.CatalogLine("line", "Line", listOf(
            com.wuming.tv.core.catalog.CatalogEpisode("First", "first"),
            com.wuming.tv.core.catalog.CatalogEpisode("Second", "second"),
        ))
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("provider", "Provider")
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail(item = item, lines = listOf(line))
            override fun play(flag: String, id: String): CatalogPlayback {
                if (id == "first") {
                    firstStarted.countDown()
                    check(releaseFirst.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    firstFinished.countDown()
                }
                return CatalogPlayback(supported = true, url = "https://media.example/$id.mp4")
            }
        }
        val player = com.wuming.tv.desktop.player.ExternalPlayerAdapter(
            java.nio.file.Path.of("/opt/test-player"),
            { launches += it; null },
            com.wuming.tv.desktop.player.PlaybackTargetPreparer { target, _ ->
                com.wuming.tv.desktop.player.PreparedPlaybackTarget(target)
            },
        )
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        val panel = onEdt { CatalogPanel(player, preferences) }
        fun play(index: Int) = onEdt {
            privateField<javax.swing.JList<String>>(panel, "episodes").selectedIndex = index
            panel.javaClass.getDeclaredMethod("playEpisode", Boolean::class.javaPrimitiveType).apply {
                isAccessible = true
            }.invoke(panel, false)
        }
        try {
            onEdt {
                privateFieldSetter(panel, "catalog", ExecutorCatalogGateway(listOf(executor)))
                privateFieldSetter(panel, "detail", executor.detail("show"))
                privateField<JComboBox<com.wuming.tv.core.catalog.CatalogLine>>(panel, "lines").addItem(line)
            }
            play(0)
            assertTrue(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            play(1)
            eventually { launches.size == 1 }
            releaseFirst.countDown()
            assertTrue(firstFinished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Allow SwingWorker's delayed completion callback to reach the EDT.
            Thread.sleep(150)
            onEdt { assertEquals(listOf(listOf("/opt/test-player", "https://media.example/second.mp4")), launches.toList()) }
        } finally {
            releaseFirst.countDown()
            onEdt { panel.close() }
            preferences.removeNode()
        }
    }

    @Test
    fun settingsRosterIsPreservedAcrossPartialActivationAndSearchMode() {
        val configured = listOf("first", "disabled", "pending", "last").map { key ->
            com.wuming.tv.desktop.config.DesktopTvBoxSite(
                key, "Configured $key", key != "disabled", false,
                com.wuming.tv.core.source.TvBoxSourceRuntime.SPIDER_RUNTIME_REQUIRED,
                jvmExecution = com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec(
                    key, key, "csp_Demo", artifactLocation = "https://example.test/spider.jar",
                ),
                browseEnabled = key != "disabled",
            )
        }
        val config = com.wuming.tv.desktop.config.DesktopTvBoxConfiguration(
            "https://example.test/config", "https://example.test/config", configured, emptyList(), true,
        )
        val requests = AtomicInteger()
        fun executor(key: String) = object : CatalogSourceExecutor {
            override val source = CatalogSource(key, "Different runtime name")
            override fun browseHome(): CatalogHome { requests.incrementAndGet(); return CatalogHome() }
            override fun search(query: String): List<CatalogItem> { requests.incrementAndGet(); return emptyList() }
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        val panel = onEdt { CatalogPanel(null, preferences) }
        try {
            onEdt {
                panel.useLocalConfiguration(config)
                val choices = privateField<JComboBox<CatalogSourceChoice>>(panel, "sources")
                choices.selectedIndex = 1
                panel.addLocalExecutors(listOf(executor("last"), executor("first")))
                fun displayed() = (0 until choices.itemCount).mapNotNull { choices.getItemAt(it).source }
                assertEquals(configured.map { it.key }, displayed().map { it.key })
                assertEquals(configured.map { it.name }, displayed().map { it.name })
                assertEquals("disabled", (choices.selectedItem as CatalogSourceChoice).source?.key)
                assertFalse(privateField<JButton>(panel, "search").isEnabled)
                assertTrue(privateField<JLabel>(panel, "status").text.contains("hidden by configuration"))
                assertTrue(privateField<javax.swing.JTextArea>(panel, "emptyState").isVisible)
                assertFalse(privateField<JLabel>(panel, "status").isVisible)
                assertTrue(privateField<javax.swing.JTextArea>(panel, "emptyState").text.contains("hidden by configuration"))
                choices.selectedIndex = 2
                assertTrue(privateField<JLabel>(panel, "status").text.contains("enable or check in Settings"))
                panel.focusSearch()
                assertEquals(configured.map { it.key }, displayed().map { it.key })
                assertEquals(0, requests.get())
            }
        } finally {
            onEdt { panel.close() }
            preferences.removeNode()
        }
    }

    @Test
    fun staleHistoryIdIsRefreshedByExactTitleSearch() {
        val detailCalls = mutableListOf<String>()
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("provider", "Provider")
            override fun search(query: String) = listOf(
                CatalogItem(source.key, "wrong", "A Similar Show"),
                CatalogItem(source.key, "fresh-id", "Example: Show"),
            )
            override fun detail(id: String): CatalogDetail {
                detailCalls += id
                if (id == "stale-id") throw IllegalArgumentException("empty detail")
                return CatalogDetail(item = CatalogItem(source.key, id, "Example Show"))
            }
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        val gateway = ExecutorCatalogGateway(listOf(executor))

        val detail = LibraryDetailResolver.resolve(
            gateway,
            CatalogItem(executor.source.key, "stale-id", "Example Show"),
            historyItem = true,
        )

        assertEquals("fresh-id", detail.item.id)
        assertEquals(listOf("stale-id", "fresh-id"), detailCalls)
    }

    @Test
    fun normalCatalogueFailureDoesNotTriggerHistoryRecovery() {
        var searches = 0
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("provider", "Provider")
            override fun search(query: String): List<CatalogItem> { searches++; return emptyList() }
            override fun detail(id: String): CatalogDetail = throw IllegalArgumentException("empty detail")
            override fun play(flag: String, id: String) = CatalogPlayback()
        }

        runCatching {
            LibraryDetailResolver.resolve(
                ExecutorCatalogGateway(listOf(executor)),
                CatalogItem(executor.source.key, "id", "Example"),
                historyItem = false,
            )
        }

        assertEquals(0, searches)
    }

    @Test
    fun searchDestinationRestoresResultsPageFromDetails() {
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        SwingUtilities.invokeAndWait {
            val panel = CatalogPanel(player = null, preferences = preferences)
            try {
                val browserContent = privateField<JPanel>(panel, "browserContent")
                val browserCards = privateField<CardLayout>(panel, "browserCards")
                browserCards.show(browserContent, "details")
                privateFieldSetter(panel, "showingDetails", true)

                panel.focusSearch()

                assertTrue(browserContent.components[0].isVisible)
                assertFalse(browserContent.components[1].isVisible)
                assertFalse(privateField<Boolean>(panel, "showingDetails"))
                assertEquals("Search", privateField<JLabel>(panel, "pageTitle").text)
            } finally {
                panel.close()
            }
        }
        preferences.removeNode()
    }

    @Test
    fun backRestoresCatalogueShownBeforeSearch() {
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("provider", "Provider")
            override fun browseHome() = CatalogHome(
                items = listOf(CatalogItem(source.key, "home", "Home title")),
            )
            override fun category(typeId: String, page: Int, filters: Map<String, String>) = CatalogPage()
            override fun search(query: String) = listOf(CatalogItem(source.key, "search", "Search result"))
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        val panel = onEdt { CatalogPanel(null, preferences).also { it.addLocalExecutor(executor) } }
        try {
            eventually { privateField<List<CatalogItem>>(panel, "items").singleOrNull()?.id == "home" }
            onEdt {
                privateField<JTextField>(panel, "query").text = "title"
                privateField<JButton>(panel, "search").doClick()
            }
            eventually { privateField<List<CatalogItem>>(panel, "items").singleOrNull()?.id == "search" }
            val back = privateField<JButton>(panel, "categoryBack")
            assertTrue(onEdt { back.isEnabled })

            onEdt { back.doClick() }

            eventually { privateField<List<CatalogItem>>(panel, "items").singleOrNull()?.id == "home" }
            assertEquals("title", onEdt { privateField<JTextField>(panel, "query").text })
        } finally {
            onEdt { panel.close() }
            preferences.removeNode()
        }
    }

    @Test
    fun vodHomeUsesOnlyFirstGenuineProvider() {
        val ordinaryHomeCalls = AtomicInteger()
        fun executor(key: String, type: CatalogType, indexProvider: Boolean) = object : CatalogSourceExecutor {
            override val source = CatalogSource(key, key, searchable = !indexProvider, indexProvider = indexProvider)
            override fun browseHome(): CatalogHome {
                if (!indexProvider) ordinaryHomeCalls.incrementAndGet()
                return CatalogHome(types = listOf(type))
            }
            override fun category(typeId: String, page: Int, filters: Map<String, String>) = CatalogPage()
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        val panel = onEdt {
            CatalogPanel(null, preferences).also {
                it.addLocalExecutors(listOf(
                    executor("ordinary", CatalogType("ordinary-type", "Ordinary"), false),
                    executor("home", CatalogType("home-type", "Home"), true),
                ))
            }
        }
        try {
            eventually { privateField<JComboBox<*>>(panel, "types").itemCount == 1 }
            assertEquals(1, ordinaryHomeCalls.get())
        } finally {
            onEdt { panel.close() }
            preferences.removeNode()
        }
    }

    @Test
    fun singletonHomeCardFallsBackToFirstCategoryVideos() {
        val categoryCalls = AtomicInteger()
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("selector", "Selector", searchable = false, indexProvider = true)
            override fun browseHome() = CatalogHome(
                items = listOf(CatalogItem(source.key, "switch", "Switch source")),
                types = listOf(CatalogType("recommended", "Recommended")),
            )
            override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage {
                categoryCalls.incrementAndGet()
                return CatalogPage(items = listOf(
                    CatalogItem(source.key, "one", "One"),
                    CatalogItem(source.key, "two", "Two"),
                ))
            }
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        val panel = onEdt { CatalogPanel(null, preferences).also { it.addLocalExecutor(executor) } }
        try {
            eventually { privateField<List<CatalogItem>>(panel, "items").map(CatalogItem::id) == listOf("one", "two") }
            assertEquals(1, categoryCalls.get())
            onEdt {
                assertFalse(privateField<javax.swing.JTextArea>(panel, "emptyState").isVisible)
                assertTrue(privateField<JLabel>(panel, "status").isVisible)
            }
        } finally {
            onEdt { panel.close() }
            preferences.removeNode()
        }
    }

    @Test
    fun providerFilterRowsUseLeanbackToggleAndResetToFirstPage() {
        val calls = CopyOnWriteArrayList<Pair<Int, Map<String, String>>>()
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("filtered", "Filtered")

            override fun browseHome() = CatalogHome(
                types = listOf(CatalogType(
                    id = "movie",
                    name = "Movies",
                    filters = listOf(CatalogFilter(
                        key = "area",
                        name = "Area",
                        initialValue = "cn",
                        values = listOf(
                            CatalogFilterValue("All", ""),
                            CatalogFilterValue("China", "cn"),
                        ),
                    )),
                )),
            )

            override fun category(typeId: String, page: Int, filters: Map<String, String>): CatalogPage {
                calls += page to filters.toMap()
                return CatalogPage(listOf(CatalogItem(source.key, "$typeId-$page", "Result")), page, 1)
            }

            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        val panel = onEdt { CatalogPanel(null, preferences).also { it.addLocalExecutor(executor) } }
        try {
            eventually { privateField<JComboBox<*>>(panel, "types").itemCount == 1 }
            val typeBar = privateField<Container>(panel, "typeBar")
            val movies = onEdt {
                descendants(typeBar).filterIsInstance<JToggleButton>().first { it.text.endsWith("Movies") }
            }
            onEdt { movies.doClick() }
            eventually { calls.lastOrNull() == (1 to mapOf("area" to "cn")) }

            val filterBar = privateField<Container>(panel, "filterBar")
            assertTrue(onEdt { filterBar.isVisible })
            val china = onEdt { descendants(filterBar).filterIsInstance<JToggleButton>().first { it.text == "China" } }
            assertTrue(china.isSelected)
            onEdt { china.doClick() }
            eventually { calls.lastOrNull() == (1 to emptyMap<String, String>()) }

            val all = onEdt { descendants(filterBar).filterIsInstance<JToggleButton>().first { it.text == "All" } }
            onEdt { all.doClick() }
            eventually { calls.lastOrNull() == (1 to mapOf("area" to "")) }
            assertEquals(3, calls.size)
        } finally {
            onEdt { panel.close() }
            preferences.removeNode()
        }
    }

    @Test
    fun indexProviderCardSearchesAllSearchableProvidersInsteadOfLoadingDetail() {
        val indexSearchCalls = AtomicInteger()
        val searched = CopyOnWriteArrayList<String>()
        val discovery = object : CatalogSourceExecutor {
            override val source = CatalogSource("discovery", "Discovery", searchable = false, indexProvider = true)
            override fun browseHome() = CatalogHome(
                items = listOf(CatalogItem(source.key, "opaque", "A discovery title")),
            )
            override fun category(typeId: String, page: Int, filters: Map<String, String>) = CatalogPage()
            override fun search(query: String): List<CatalogItem> {
                indexSearchCalls.incrementAndGet()
                return emptyList()
            }
            override fun detail(id: String): CatalogDetail = error("index detail must not run")
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        val provider = object : CatalogSourceExecutor {
            override val source = CatalogSource("provider", "Provider", searchable = true)
            override fun browseHome() = CatalogHome()
            override fun category(typeId: String, page: Int, filters: Map<String, String>) = CatalogPage()
            override fun search(query: String): List<CatalogItem> {
                searched += query
                return emptyList()
            }
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        val preferences = Preferences.userRoot().node("tvbox-desktop-test/${System.nanoTime()}")
        val panel = onEdt { CatalogPanel(null, preferences).also { it.addLocalExecutors(listOf(discovery, provider)) } }
        try {
            eventually { privateField<List<CatalogItem>>(panel, "items").any { it.sourceKey == discovery.source.key } }
            onEdt {
                val results = privateField<javax.swing.JList<*>>(panel, "results")
                results.selectedIndex = privateField<List<CatalogItem>>(panel, "items")
                    .indexOfFirst { it.sourceKey == discovery.source.key }
                invoke(panel, "activateSelection")
            }
            eventually { searched.lastOrNull() == "A discovery title" }
            assertEquals(0, indexSearchCalls.get())
        } finally {
            onEdt { panel.close() }
            preferences.removeNode()
        }
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            if (onEdt(condition)) return
            Thread.sleep(20)
        }
        assertTrue("condition did not become true", onEdt(condition))
    }

    private fun descendants(root: Container): List<Component> = root.components.flatMap { component ->
        listOf(component) + if (component is Container) descendants(component) else emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> privateField(target: Any, name: String): T = target.javaClass.getDeclaredField(name).let { field ->
        field.isAccessible = true
        field.get(target) as T
    }

    private fun invoke(target: Any, name: String) = target.javaClass.getDeclaredMethod(name).let { method ->
        method.isAccessible = true
        method.invoke(target)
    }

    private fun privateFieldSetter(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).let { field ->
            field.isAccessible = true
            field.set(target, value)
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
