package com.wuming.tv.core.design

/** Stable persisted IDs. Unknown values from newer versions safely use platform defaults. */
enum class Appearance(val id: Int) {
    SYSTEM(0), LIGHT(1), DARK(2);
    companion object {
        @JvmStatic fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class ColorTheme(val id: Int) {
    CLASSIC(0), AURORA(1), PROFESSIONAL(2);
    companion object {
        @JvmStatic fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

object ThemePolicy {
    @JvmStatic fun palette(theme: ColorTheme, appearance: Appearance, systemDark: Boolean): ThemePalette {
        val dark = appearance == Appearance.DARK || (appearance == Appearance.SYSTEM && systemDark)
        return when (theme) {
            ColorTheme.CLASSIC -> if (dark) DesignTokens.CLASSIC_DARK else DesignTokens.CLASSIC_LIGHT
            ColorTheme.AURORA -> if (dark) DesignTokens.AURORA_DARK else DesignTokens.AURORA_LIGHT
            // Preserve the reserved theme ID until its own palettes are available.
            ColorTheme.PROFESSIONAL -> if (dark) DesignTokens.CLASSIC_DARK else DesignTokens.CLASSIC_LIGHT
        }
    }
}

enum class WindowSize { COMPACT, MEDIUM, EXPANDED }

/** Dimensions are logical units: Android dp and Swing user-space pixels, never physical pixels. */
object LayoutPolicy {
    const val MOBILE_PORTRAIT_COLUMNS = 3
    const val MOBILE_LANDSCAPE_COLUMNS = 5
    const val TV_COLUMNS = 5
    const val POSTER_WIDTH_TO_HEIGHT = 3f / 4f

    @JvmStatic fun windowSize(width: Int): WindowSize = when {
        width < 600 -> WindowSize.COMPACT
        width < 1000 -> WindowSize.MEDIUM
        else -> WindowSize.EXPANDED
    }

    /** Bound navigation on ultrawide desktops so extra space belongs to content. */
    @JvmStatic fun desktopRailWidth(width: Int): Int =
        (width.coerceAtLeast(0) * 0.16).toInt().coerceIn(176, 256).coerceAtMost(width.coerceAtLeast(0))

    /** Responsive form width with edge gutters; safe before the first measurement. */
    @JvmStatic fun formWidth(width: Int): Int =
        (width.coerceAtLeast(0).toLong() - 2 * DesignTokens.SPACE_LARGE).coerceIn(0, 640).toInt()
}
