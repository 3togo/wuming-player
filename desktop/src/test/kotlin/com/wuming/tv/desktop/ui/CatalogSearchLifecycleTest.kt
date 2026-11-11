package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import javax.swing.*
import org.junit.Assert.*
import org.junit.Test

class CatalogSearchLifecycleTest {
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(panel: CatalogPanel, name: String): T =
        panel.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(panel) as T
    private fun edt(block: () -> Unit) = SwingUtilities.invokeAndWait(block)
    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            var ready = false
            edt { ready = condition() }
            if (ready) return
            Thread.sleep(10)
        }
        fail(description)
    }

    @Test fun replacementInterruptsOldProviderAndLateReturnCannotReplaceVisibleResults() {
        val prefs = Preferences.userRoot().node("tvbox-desktop-test/${UUID.randomUUID()}")
        lateinit var panel: CatalogPanel
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val provider = object : CatalogSourceExecutor {
            override val source = CatalogSource("fixture", "Fixture")
            override fun home() = listOf(CatalogItem(source.key, "home", "Home"))
            override fun search(query: String): List<CatalogItem> {
                if (query == "old") {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { interrupted.countDown() }
                    returned.countDown()
                }
                return listOf(CatalogItem(source.key, query, query))
            }
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        try {
            edt { panel = CatalogPanel(null, prefs); panel.addLocalExecutor(provider) }
            await("Home did not load") { field<JList<CatalogItem>>(panel, "results").model.size == 1 }
            edt {
                panel.focusSearch()
                field<JTextField>(panel, "query").text = "old"
                field<JButton>(panel, "search").doClick()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            edt {
                field<JTextField>(panel, "query").text = "new"
                field<JButton>(panel, "search").doClick()
            }
            assertTrue("Replaced search must interrupt its provider", interrupted.await(5, TimeUnit.SECONDS))
            await("New search did not publish") {
                field<JList<CatalogItem>>(panel, "results").model.let { it.size == 1 && it.getElementAt(0).id == "new" }
            }
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            edt {
                assertEquals("new", field<JList<CatalogItem>>(panel, "results").model.getElementAt(0).id)
                assertTrue(field<JLabel>(panel, "status").text.contains("Found 1"))
            }
        } finally {
            release.countDown()
            edt { panel.close() }
            prefs.removeNode()
        }
    }
}
