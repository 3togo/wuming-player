package com.wuming.tv.desktop.ui

import java.awt.RenderingHints
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import javax.swing.ImageIcon

/** Swing image port: retain detailed pixels independently of the icon's logical size. */
internal object PosterImages {
    const val PIXEL_WIDTH = CatalogThumbnailCache.WIDTH * 4
    const val PIXEL_HEIGHT = CatalogThumbnailCache.HEIGHT * 4

    fun pixels(icon: ImageIcon): BufferedImage? = when (val image = icon.image) {
        is MultiResolutionImage -> image.resolutionVariants.last() as? BufferedImage
        is BufferedImage -> image
        else -> null
    }

    fun icon(source: BufferedImage, width: Int = CatalogThumbnailCache.WIDTH,
             height: Int = CatalogThumbnailCache.HEIGHT): ImageIcon {
        val base = resize(source, width, height)
        // Supply an exactly sized 2x variant for the common HiDPI case. Keep the
        // original pixels too, for larger monitor scales and future card resizing.
        val doubleWidth = width * 2
        val doubleHeight = height * 2
        val variants = mutableListOf(base)
        if (doubleWidth < source.width && doubleHeight < source.height) {
            variants += resize(source, doubleWidth, doubleHeight)
        }
        if (source.width > width && source.height > height) variants += source
        return object : ImageIcon(BaseMultiResolutionImage(*variants.toTypedArray())) {
            override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
                val qualityGraphics = graphics.create() as Graphics2D
                try {
                    qualityGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    qualityGraphics.setRenderingHint(RenderingHints.KEY_RESOLUTION_VARIANT, RenderingHints.VALUE_RESOLUTION_VARIANT_SIZE_FIT)
                    super.paintIcon(component, qualityGraphics, x, y)
                } finally {
                    qualityGraphics.dispose()
                }
            }
        }
    }

    /** Halving before the final bicubic pass filters detail instead of skipping source pixels. */
    fun resize(source: BufferedImage, width: Int, height: Int): BufferedImage {
        require(width > 0 && height > 0)
        var current = source
        while (current.width != width || current.height != height) {
            val nextWidth = maxOf(width, current.width / 2)
            val nextHeight = maxOf(height, current.height / 2)
            val next = BufferedImage(nextWidth, nextHeight, BufferedImage.TYPE_INT_ARGB)
            val graphics = next.createGraphics()
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                graphics.drawImage(current, 0, 0, nextWidth, nextHeight, null)
            } finally {
                graphics.dispose()
            }
            current = next
        }
        return current
    }
}
