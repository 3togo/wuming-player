package com.wuming.tv.desktop.ui

import com.wuming.tv.core.design.Appearance
import com.wuming.tv.core.design.ColorTheme
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.text.StyleConstants
import org.junit.Assert.*
import org.junit.Test

class CatalogDetailsPaneTest {
    @Test fun rendersChineseParagraphsAndEmphasisAndPreservesThemeRefresh() = SwingUtilities.invokeAndWait {
        for (appearance in listOf(Appearance.LIGHT, Appearance.DARK)) {
            DesktopTvTheme.install(appearance = appearance)
            val defaultKit = javax.swing.text.html.HTMLEditorKit()
            val margin = defaultKit.styleSheet.getRule("p").getAttribute(javax.swing.text.html.CSS.Attribute.MARGIN_TOP)?.toString()
            val pane = CatalogDetailsPane()
            assertEquals("Description styling must not mutate Swing's shared HTML defaults", margin,
                defaultKit.styleSheet.getRule("p").getAttribute(javax.swing.text.html.CSS.Attribute.MARGIN_TOP)?.toString())
            pane.showDescription("里米 & 電影 <特輯>", listOf("Cast: 喬什 · 哈奈特", "Director: 電影導演"),
                "<p>那些扭曲駭人的告白，難分現實與虛構，該如何看清真相？<br/>第二行文字</p>" +
                    "<p>影片改編自<b>同名暢銷小說</b>。</p><ul><li>第一點</li><li>第二點</li></ul>")
            DesktopTvTheme.scaleExplicitMetrics(pane)
            pane.setSize(DesktopTvTheme.px(460), DesktopTvTheme.px(520))
            val document = pane.document
            val visible = document.getText(0, document.length)
            assertTrue(visible.contains("里米 & 電影 <特輯>"))
            assertTrue(visible.contains("第二行文字"))
            assertTrue(visible.contains("同名暢銷小說"))
            assertFalse(visible.contains("<br"))
            assertFalse(visible.contains("</p>"))
            val first = pane.modelToView2D(visible.indexOf("那些")).bounds
            val second = pane.modelToView2D(visible.indexOf("第二行")).bounds
            assertTrue("br creates a visible line break", second.y > first.y)
            val html = document as javax.swing.text.html.HTMLDocument
            assertTrue(StyleConstants.isBold(html.getCharacterElement(visible.indexOf("同名暢銷小說")).attributes))
            pane.caretPosition = 5
            val next = if (appearance == Appearance.LIGHT) Appearance.DARK else Appearance.LIGHT
            DesktopTvTheme.refresh(ColorTheme.CLASSIC, next, next == Appearance.DARK, listOf(pane))
            assertSame(document, pane.document)
            assertEquals(visible, pane.document.getText(0, pane.document.length))
            assertEquals(5, pane.caretPosition)
            assertEquals(DesktopTvTheme.TEXT, pane.foreground)
            assertTrue(pane.scrollableTracksViewportWidth)
            System.getProperty("wuming.html.renderDirectory")?.let { directory ->
                Files.createDirectories(Path.of(directory))
                val image = BufferedImage(pane.width, pane.height, BufferedImage.TYPE_INT_RGB)
                image.createGraphics().let { graphics -> pane.paint(graphics); graphics.dispose() }
                ImageIO.write(image, "png", Path.of(directory, "description-${next.name.lowercase()}.png").toFile())
            }
        }
    }

    @Test fun plainPreviewsAndAuditAppendsRemainLiteral() = SwingUtilities.invokeAndWait {
        val pane = CatalogDetailsPane()
        pane.showPlainText("Source <audit> & status\nFirst line\n")
        pane.appendPlainText("Second <step>   & result\n")
        val value = pane.document.getText(0, pane.document.length)
        assertTrue(value.contains("Source <audit> & status"))
        assertTrue(value.contains("First line"))
        assertEquals("Source <audit> & status\nFirst line\nSecond <step>   & result\n", value)
        assertEquals("text/plain", pane.contentType)
        pane.showDescription("Another movie", emptyList(), "<p>簡介</p>")
        assertFalse(pane.document.getText(0, pane.document.length).contains("Source <audit>"))
        assertEquals(0, pane.caretPosition)
    }
}
