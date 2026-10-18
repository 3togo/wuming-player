package com.wuming.tv.desktop.ui

import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage
import org.junit.Assert.*
import org.junit.Test

class PosterImagesTest {
    @Test
    fun `large reductions average fine stripes rather than aliasing them`() {
        val source = BufferedImage(1088, 1472, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setRGB(x, y, if (x % 2 == 0) Color.BLACK.rgb else Color.WHITE.rgb)
        }
        val resized = PosterImages.resize(source, 136, 184)
        for (y in 2 until resized.height - 2) for (x in 2 until resized.width - 2) {
            val color = Color(resized.getRGB(x, y), true)
            assertTrue("Stripe average at $x,$y was ${color.red}", color.red in 120..136)
            assertEquals(255, color.alpha)
        }
    }

    @Test
    fun `enlarged cards and HiDPI painting use retained fine detail`() {
        val source = BufferedImage(544, 736, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) {
            source.setRGB(x, y, if ((x / 2) % 2 == 0) Color.BLACK.rgb else Color.WHITE.rgb)
        }
        val small = PosterImages.icon(source)
        assertEquals(136, small.iconWidth)
        assertEquals(184, small.iconHeight)
        val enlarged = PosterImages.icon(requireNotNull(PosterImages.pixels(small)), 272, 368)
        val variants = enlarged.image as MultiResolutionImage
        assertSame(source, variants.getResolutionVariant(544.0, 736.0))
        val painted = BufferedImage(544, 736, BufferedImage.TYPE_INT_ARGB)
        val graphics = painted.createGraphics()
        try {
            graphics.scale(2.0, 2.0)
            enlarged.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }
        for (x in 8..40) assertEquals(source.getRGB(x, 100), painted.getRGB(x, 100))
    }

    @Test
    fun `resampling keeps transparency and handles enlarging a tiny image`() {
        val source = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).apply {
            setRGB(0, 0, Color(255, 0, 0, 128).rgb)
        }
        val resized = PosterImages.resize(source, 17, 23)
        assertEquals(17, resized.width)
        assertEquals(23, resized.height)
        assertEquals(128, Color(resized.getRGB(8, 11), true).alpha)
    }
}
