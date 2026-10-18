package com.wuming.tv.desktop.ui

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.ImageIcon

/** Uses the Android launcher artwork copied into desktop resources by Gradle. */
internal object DesktopAppIcon {
    private val original: BufferedImage by lazy {
        val resource = requireNotNull(javaClass.getResourceAsStream("/icons/tvbox-desktop.png")) {
            "Shared Android launcher icon is missing."
        }
        resource.use { requireNotNull(ImageIO.read(it)) { "Shared launcher icon cannot be decoded." } }
    }

    fun image(edge: Int): BufferedImage = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB).apply {
        val canvas = createGraphics()
        try {
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            canvas.drawImage(original, 0, 0, edge, edge, null)
        } finally {
            canvas.dispose()
        }
    }

    fun icon(edge: Int): ImageIcon = ImageIcon(image(edge))

    fun windowImages(): List<BufferedImage> = listOf(16, 32, 48, 64, 128, 256).map(::image)
}
