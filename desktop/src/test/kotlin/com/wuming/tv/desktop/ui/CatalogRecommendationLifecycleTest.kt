package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.*
import com.wuming.tv.desktop.library.DesktopUserLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test

class CatalogRecommendationLifecycleTest {
    @Test fun anOldProviderFailureCannotReplaceThePrivateRecommendationResult() {
        val dir = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("user.home"), "temp")), "recommendation-epoch-")
        val preferences = Preferences.userRoot().node("wuming-recommendation-epoch/${System.nanoTime()}")
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val delivered = java.util.concurrent.CountDownLatch(1)
        val logger = java.util.logging.Logger.getLogger("tvbox.ui.catalog")
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) {
                if (record.message.contains("obsolete recommendation failure")) delivered.countDown()
            }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val provider = object : CatalogSourceExecutor {
            override val source = CatalogSource("douban", "Douban")
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
            override fun categoryWithRecommendationSeeds(typeId: String, page: Int, filters: Map<String,String>, seeds: List<String>): CatalogPage {
                if (calls.incrementAndGet() == 1) {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("obsolete recommendation failure")
                }
                return CatalogPage(listOf(CatalogItem("douban", "anonymous", "Anonymous")), page = 1, pageCount = 1)
            }
        }
        lateinit var panel: CatalogPanel
        fun field(name: String) = panel.javaClass.getDeclaredField(name).apply { isAccessible = true }
        try {
            SwingUtilities.invokeAndWait {
                panel = CatalogPanel(null, preferences, userLibrary = DesktopUserLibrary.open(dir.resolve("library.json")))
                field("catalog").set(panel, ExecutorCatalogGateway(listOf(provider)))
                field("currentCategorySourceKey").set(panel, "douban")
                field("currentCategoryId").set(panel, "guess_you_like")
                field("currentCategoryType").set(panel, CatalogType("guess_you_like", "Recommended"))
                panel.javaClass.getDeclaredMethod("loadCategory", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(panel, 1, true)
            }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait { (field("privateViewing").get(panel) as JCheckBox).doClick() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var ready = false
            while (!ready && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait {
                    val results = field("results").get(panel) as JList<CatalogItem>
                    ready = results.model.size > 0 && results.model.getElementAt(0).name == "Anonymous"
                }
                if (!ready) Thread.sleep(10)
            }
            assertTrue("Private request was blocked by the old provider", ready)
            release.countDown()
            assertTrue("Old failure did not reach its delivery callback", delivered.await(3, TimeUnit.SECONDS))
            SwingUtilities.invokeAndWait {
                assertFalse((field("status").get(panel) as JLabel).text.contains("obsolete recommendation failure"))
                assertEquals("Anonymous", (field("results").get(panel) as JList<CatalogItem>).model.getElementAt(0).name)
            }
        } finally {
            release.countDown()
            SwingUtilities.invokeAndWait { panel.close() }
            logger.removeHandler(handler)
            preferences.removeNode()
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun privateViewingAndHistoryDeletionRefreshTheRecommendationPager() {
        val dir = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("user.home"), "temp")), "recommendation-ui-")
        val preferences = Preferences.userRoot().node("wuming-recommendation-test/${System.nanoTime()}")
        val library = DesktopUserLibrary.open(dir.resolve("library.json"))
        val calls = CopyOnWriteArrayList<List<String>>()
        val provider = object : CatalogSourceExecutor {
            override val source = CatalogSource("douban", "Douban")
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
            override fun categoryWithRecommendationSeeds(typeId: String, page: Int, filters: Map<String,String>, seeds: List<String>): CatalogPage {
                calls += seeds.toList()
                return CatalogPage(listOf(CatalogItem("douban", "result", seeds.firstOrNull() ?: "Anonymous")), page = page, pageCount = 1)
            }
        }
        val episode = CatalogEpisode("E1", "e1")
        library.recordPlayback(CatalogItem("douban", "watched", "Watched title"), CatalogLine("direct", episodes = listOf(episode)), episode)
        lateinit var panel: CatalogPanel
        fun field(name: String) = panel.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun awaitResult(name: String, count: Int) {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < end) {
                var ready = false
                SwingUtilities.invokeAndWait {
                    val results = field("results").get(panel) as JList<CatalogItem>
                    ready = calls.size >= count && !field("categoryLoading").getBoolean(panel) && results.model.size > 0 && results.model.getElementAt(0).name == name
                }
                if (ready) return
                Thread.sleep(10)
            }
            fail("Expected $name after $count provider calls; observed $calls")
        }
        try {
            SwingUtilities.invokeAndWait {
                panel = CatalogPanel(null, preferences, userLibrary = library)
                field("catalog").set(panel, ExecutorCatalogGateway(listOf(provider)))
                field("currentCategorySourceKey").set(panel, "douban")
                field("currentCategoryId").set(panel, "guess_you_like")
                field("currentCategoryType").set(panel, CatalogType("guess_you_like", "Recommended"))
                panel.javaClass.getDeclaredMethod("loadCategory", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(panel, 1, true)
            }
            awaitResult("Watched title", 1)
            var originalSnapshot: Any? = null
            SwingUtilities.invokeAndWait {
                originalSnapshot = panel.javaClass.getDeclaredMethod("currentSnapshot").apply { isAccessible = true }.invoke(panel)
                (field("privateViewing").get(panel) as JCheckBox).doClick()
                assertEquals(0, (field("results").get(panel) as JList<*>).model.size)
            }
            awaitResult("Anonymous", 2)
            SwingUtilities.invokeAndWait {
                val snapshot = originalSnapshot!!
                panel.javaClass.declaredMethods.single { it.name == "restoreBrowseSnapshot" }.apply { isAccessible = true }.invoke(panel, snapshot)
            }
            awaitResult("Anonymous", 2)
            assertEquals(emptyList<String>(), calls.last())
            library.clearHistory()
            SwingUtilities.invokeAndWait { (field("privateViewing").get(panel) as JCheckBox).doClick() }
            awaitResult("Anonymous", 3)
            assertEquals(emptyList<String>(), calls.last())
        } finally {
            SwingUtilities.invokeAndWait { panel.close() }
            preferences.removeNode()
            dir.toFile().deleteRecursively()
        }
    }
}
