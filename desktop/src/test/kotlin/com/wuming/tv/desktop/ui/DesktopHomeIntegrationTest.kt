package com.wuming.tv.desktop.ui

import com.wuming.tv.core.catalog.*
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.JList
import javax.swing.SwingUtilities
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

/** Run with a window server and java.awt.headless=false; no remote sources or saved profile. */
class DesktopHomeIntegrationTest {
    @Test fun returningHomeShowsCataloguePostersIncludingAfterSearchAndLibrary() {
        assumeFalse(GraphicsEnvironment.isHeadless())
        val directory = java.nio.file.Files.createTempDirectory("wuming-home-ui-")
        val prefs = Preferences.userRoot().node("wuming-home-test/${System.nanoTime()}")
        val bytes = ByteArrayOutputStream().also { output ->
            val image = BufferedImage(24, 36, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { g -> g.color = Color.GREEN; g.fillRect(0, 0, 24, 36); g.dispose() }
            ImageIO.write(image, "png", output)
        }.toByteArray()
        val picture = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
        val executor = object : CatalogSourceExecutor {
            override val source = CatalogSource("offline-home", "Offline home")
            override fun browseHome() = CatalogHome(
                items = listOf(CatalogItem(source.key, "film", "Film", picture = picture)),
                types = List(12) { CatalogType("type-$it", "热播电影分类 $it") },
            )
            override fun search(query: String) = emptyList<CatalogItem>()
            override fun detail(id: String) = CatalogDetail()
            override fun play(flag: String, id: String) = CatalogPlayback()
        }
        lateinit var window: DesktopWindow
        lateinit var frame: JFrame
        lateinit var catalog: CatalogPanel
        var created = false
        try {
            SwingUtilities.invokeAndWait {
                DesktopTvTheme.install()
                window = DesktopWindow(null, prefs, com.wuming.tv.desktop.server.DesktopLanRelay(
                    com.wuming.tv.desktop.server.DesktopLanRelaySettings(directory.resolve("relay.properties"))))
                frame = field(window, "frame")
                catalog = field(window, "catalog")
                created = true
                frame.isVisible = true // Avoid loading the user's saved sources.
                catalog.addLocalExecutor(executor)
            }
            for (destination in listOf("initial", "search", "library")) {
                SwingUtilities.invokeAndWait {
                    when (destination) {
                        "search" -> catalog.focusSearch()
                        "library" -> catalog.showKeep()
                    }
                    window.javaClass.getDeclaredMethod("showHome").apply { isAccessible = true }.invoke(window)
                    assertTrue("Home must show the catalogue", catalog.isShowing)
                }
                val deadline = System.nanoTime() + 5_000_000_000L
                var ready = false
                while (!ready && System.nanoTime() < deadline) {
                    SwingUtilities.invokeAndWait {
                        val results: JList<CatalogItem> = field(catalog, "results")
                        val thumbnails: CatalogThumbnailCache = field(catalog, "thumbnails")
                        val typeBar: javax.swing.JPanel = field(catalog, "typeBar")
                        ready = results.model.size == 1 && results.model.getElementAt(0).id == "film" &&
                            typeBar.componentCount == 12 &&
                            PosterImages.pixels(thumbnails.iconFor(picture))?.getRGB(20, 20) == Color.GREEN.rgb
                    }
                    if (!ready) Thread.sleep(20)
                }
                assertTrue("Home poster missing after $destination", ready)
                SwingUtilities.invokeAndWait {
                    frame.setSize(DesktopTvTheme.px(900), DesktopTvTheme.px(850))
                    frame.validate()
                    val row: javax.swing.JPanel = field(catalog, "typeBar")
                    val scroll: javax.swing.JScrollPane = field(catalog, "typeScroll")
                    assertTrue("Category rail should overflow horizontally", scroll.horizontalScrollBar.isVisible)
                    row.components.forEach { chip ->
                        assertTrue("Category caption clipped above viewport", chip.y >= 0)
                        assertTrue("Category caption clipped below viewport",
                            chip.y + chip.height <= scroll.viewport.extentSize.height)
                        assertTrue("Category control lost its preferred height", chip.height >= chip.preferredSize.height)
                    }
                    val rendered = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
                    rendered.createGraphics().let { g -> frame.printAll(g); g.dispose() }
                    var posterPixels = 0
                    for (y in 0 until rendered.height) for (x in 0 until rendered.width) {
                        if (rendered.getRGB(x, y) == Color.GREEN.rgb) posterPixels++
                    }
                    assertTrue("Home must paint the poster after $destination", posterPixels > 1_000)
                    System.getenv("TVBOX_DESKTOP_RENDER_DIR")?.let { directory ->
                        val file = java.io.File(directory, "home-$destination.png")
                        file.parentFile.mkdirs()
                        ImageIO.write(rendered, "png", file)
                    }
                }
            }
        } finally {
            SwingUtilities.invokeAndWait {
                if (created) frame.dispatchEvent(java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING))
            }
            prefs.removeNode()
            directory.toFile().deleteRecursively()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(instance: Any, name: String): T = instance.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(instance) as T
    }
}
