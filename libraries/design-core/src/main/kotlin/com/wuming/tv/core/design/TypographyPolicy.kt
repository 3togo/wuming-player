package com.wuming.tv.core.design

/** Semantic logical sizes; adapters convert to pixels once, independently of layout density. */
enum class TextRole(val mobile: Int, val television: Int) {
    DISPLAY(DesignTokens.UI_DISPLAY_TEXT, DesignTokens.TV_DISPLAY_TEXT),
    HEADLINE(DesignTokens.UI_HEADLINE_TEXT, DesignTokens.TV_HEADLINE_TEXT),
    TITLE(DesignTokens.UI_TITLE_TEXT, DesignTokens.TV_TITLE_TEXT),
    BODY(DesignTokens.UI_BODY_TEXT, DesignTokens.TV_BODY_TEXT),
    LABEL(DesignTokens.UI_LABEL_TEXT, DesignTokens.TV_LABEL_TEXT),
    CAPTION(DesignTokens.UI_CAPTION_TEXT, DesignTokens.TV_CAPTION_TEXT),
    MICRO(DesignTokens.UI_MICRO_TEXT, DesignTokens.TV_MICRO_TEXT);

    companion object {
        fun fromName(name: String?): TextRole? = entries.firstOrNull { it.name.equals(name, true) }
    }
}

object TypographyPolicy {
    /** Use the native smallest width, never the current orientation or the content string. */
    fun tabletScale(smallestWidth: Float, baseline: Float = 360f, cap: Float = 1.25f): Float {
        val safeBaseline = baseline.takeIf { it.isFinite() }?.coerceIn(240f, 1000f) ?: 360f
        val safeCap = cap.takeIf { it.isFinite() }?.coerceIn(1f, 1.5f) ?: 1.25f
        val width = smallestWidth.takeIf { it.isFinite() } ?: safeBaseline
        val progress = ((width - safeBaseline) / (1100f - safeBaseline)).coerceIn(0f, 1f)
        return 1f + (safeCap - 1f) * progress
    }

    fun logicalSize(role: TextRole, television: Boolean = false): Float =
        (if (television) role.television else role.mobile).toFloat()
}
