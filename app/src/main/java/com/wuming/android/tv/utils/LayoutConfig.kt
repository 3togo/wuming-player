package com.wuming.android.tv.utils

import com.wuming.android.tv.App
import com.wuming.android.tv.R

import com.wuming.tv.core.design.LayoutPolicy

/**
 * Centralized layout configuration for two-column search pages.
 * All layout ratios and dimensions that are calculated programmatically
 * should be defined here so they can be tuned in one place.
 *
 * XML layout files that have values overridden programmatically will
 * contain comments referencing the corresponding config constant.
 */
object LayoutConfig {

    // ===== Shared proportional spacing =====

    private const val TYPE_CHIP_SIDE_MARGIN_RATIO = 1f / 8f
    private const val TYPE_CHIP_HORIZONTAL_PADDING_RATIO = 1f / 3f
    private const val RANKING_VERTICAL_PADDING_RATIO = 0.5f

    @JvmStatic
    fun getTypeChipSideMarginPixels(itemHeight: Int): Int {
        return Math.max(0, Math.round(itemHeight * TYPE_CHIP_SIDE_MARGIN_RATIO))
    }

    @JvmStatic
    fun getTypeChipHorizontalPaddingPixels(itemHeight: Int): Int {
        return Math.max(0, Math.round(itemHeight * TYPE_CHIP_HORIZONTAL_PADDING_RATIO))
    }

    @JvmStatic
    fun getRankingVerticalPaddingPixels(horizontalPadding: Int): Int {
        return Math.max(0, Math.round(horizontalPadding * RANKING_VERTICAL_PADDING_RATIO))
    }

    // ===== Live guide columns =====

    private const val LIVE_COLUMN_MIN_WIDTH_DP = 120
    private const val LIVE_COLUMN_MAX_WIDTH_DP = 300
    private const val LIVE_COLUMN_HORIZONTAL_PADDING_DP = 32
    private const val LIVE_CHANNEL_WIDTH_PER_CHARACTER_DP = 14
    private const val LIVE_EPG_WIDTH_PER_CHARACTER_DP = 12
    private const val LIVE_CHANNEL_COLUMN_COUNT = 2

    private fun liveColumnWidthDp(maxCharacters: Int, widthPerCharacterDp: Int): Int {
        val contentWidth = maxCharacters * widthPerCharacterDp + LIVE_COLUMN_HORIZONTAL_PADDING_DP
        return Math.max(LIVE_COLUMN_MIN_WIDTH_DP, Math.min(contentWidth, LIVE_COLUMN_MAX_WIDTH_DP))
    }

    @JvmStatic
    fun getLiveGroupWidthDp(maxCharacters: Int): Int {
        return liveColumnWidthDp(maxCharacters, LIVE_CHANNEL_WIDTH_PER_CHARACTER_DP)
    }

    @JvmStatic
    fun getLiveChannelWidthDp(maxCharacters: Int): Int {
        return getLiveGroupWidthDp(maxCharacters) * LIVE_CHANNEL_COLUMN_COUNT
    }

    @JvmStatic
    fun getLiveEpgWidthDp(maxCharacters: Int): Int {
        return liveColumnWidthDp(maxCharacters, LIVE_EPG_WIDTH_PER_CHARACTER_DP)
    }

    // ===== Mobile Home Navigation =====

    /** Compact landscape rail width measured against the physical screen. */
    @JvmField
    val MOBILE_NAVIGATION_RAIL_WIDTH_RATIO = 0.085f

    /** Active pill leaves equal breathing room on both sides of the rail. */
    @JvmField
    val MOBILE_NAVIGATION_INDICATOR_WIDTH_RATIO = 0.70f
    @JvmField
    val MOBILE_NAVIGATION_INDICATOR_HEIGHT_RATIO = 0.40f
    @JvmField
    val MOBILE_NAVIGATION_ICON_SIZE_RATIO = 0.30f
    @JvmField
    val MOBILE_NAVIGATION_ITEM_HEIGHT_RATIO = 0.23f
    @JvmField
    val MOBILE_NAVIGATION_TEXT_SIZE_RATIO = 0.04f
    @JvmField
    val MOBILE_NAVIGATION_ENGLISH_TEXT_SIZE_RATIO = 0.027f

    /** Height-based sizing avoids width-driven AutoSize inflation on wide phones. */
    @JvmField
    val MOBILE_HOME_TOOLBAR_HEIGHT_RATIO = 0.115f
    @JvmField
    val MOBILE_HOME_CATEGORY_ROW_HEIGHT_RATIO = 0.09f
    @JvmField
    val MOBILE_HOME_CATEGORY_CHIP_HEIGHT_RATIO = 0.067f
    @JvmField
    val MOBILE_HOME_LOGO_SIZE_RATIO = 0.68f
    @JvmField
    val MOBILE_HOME_TOOLBAR_CONTENT_INSET_RATIO = 0.2f
    @JvmField
    val MOBILE_HOME_CATEGORY_INSET_ADJUSTMENT_RATIO = 0.125f
    @JvmField
    val MOBILE_HOME_FAB_SIZE_RATIO = 0.096f
    @JvmField
    val MOBILE_HOME_FAB_ICON_RATIO = 0.041f
    @JvmField
    val MOBILE_HOME_FAB_MARGIN_RATIO = 0.025f
    @JvmField
    val MOBILE_PORTRAIT_TITLE_MAX_WIDTH_RATIO = 0.46f
    @JvmField
    val MOBILE_PORTRAIT_FAB_SIZE_RATIO = 0.13f
    @JvmField
    val MOBILE_PORTRAIT_FAB_ICON_RATIO = 0.055f
    @JvmField
    val MOBILE_PORTRAIT_FAB_MARGIN_RATIO = 0.025f
    @JvmField
    val MOBILE_VIDEO_LANDSCAPE_PLAYER_WEIGHT = 0.6f
    @JvmField
    val MOBILE_VIDEO_LANDSCAPE_DETAIL_WEIGHT = 0.4f
    @JvmField
    val MOBILE_VIDEO_PORTRAIT_DETAIL_WEIGHT = 1f

    @JvmStatic
    fun getMobileNavigationRailWidthPixels(context: android.content.Context): Int {
        return mobileNavigationRailWidth(ResUtil.getScreenWidth(context))
    }

    @JvmStatic
    fun mobileNavigationRailWidth(screenWidth: Int): Int {
        return Math.max(1, Math.round(Math.max(1, screenWidth) * MOBILE_NAVIGATION_RAIL_WIDTH_RATIO))
    }

    @JvmStatic
    fun getMobileHomeToolbarHeightPixels(context: android.content.Context): Int {
        return scaledHeight(ResUtil.getScreenHeight(context), MOBILE_HOME_TOOLBAR_HEIGHT_RATIO)
    }

    @JvmStatic
    fun getMobileHomeCategoryRowHeightPixels(context: android.content.Context): Int {
        return scaledHeight(ResUtil.getScreenHeight(context), MOBILE_HOME_CATEGORY_ROW_HEIGHT_RATIO)
    }

    @JvmStatic
    fun getMobileHomeCategoryChipHeightPixels(context: android.content.Context): Int {
        return scaledHeight(ResUtil.getScreenHeight(context), MOBILE_HOME_CATEGORY_CHIP_HEIGHT_RATIO)
    }

    @JvmStatic
    fun getMobileHomeTitleTextSizePixels(context: android.content.Context): Int {
        return Math.round(TextScale.pixels(context, com.wuming.tv.core.design.TextRole.HEADLINE))
    }

    @JvmStatic
    fun getMobileHomeCategoryTextSizePixels(context: android.content.Context): Int {
        return Math.round(TextScale.pixels(context, com.wuming.tv.core.design.TextRole.LABEL))
    }

    @JvmStatic
    fun getMobileHomeLogoSizePixels(toolbarHeight: Int): Int {
        return Math.max(1, Math.round(toolbarHeight * MOBILE_HOME_LOGO_SIZE_RATIO))
    }

    @JvmStatic
    fun getMobileHomeToolbarContentInsetPixels(toolbarHeight: Int): Int {
        return Math.max(0, Math.round(toolbarHeight * MOBILE_HOME_TOOLBAR_CONTENT_INSET_RATIO))
    }

    @JvmStatic
    fun getMobileHomeCategoryInsetAdjustmentPixels(chipHeight: Int): Int {
        return Math.max(0, Math.round(chipHeight * MOBILE_HOME_CATEGORY_INSET_ADJUSTMENT_RATIO))
    }

    @JvmStatic
    fun getMobileNavigationItemHeightPixels(context: android.content.Context): Int {
        return scaledHeight(ResUtil.getScreenHeight(context), MOBILE_NAVIGATION_ITEM_HEIGHT_RATIO)
    }

    @JvmStatic
    fun getMobileNavigationTextSizePixels(context: android.content.Context): Int {
        // Match the label resources, including English fallback for untranslated locales.
        val ratio = if (context.resources.getBoolean(
                com.wuming.android.tv.R.bool.mobile_navigation_english_labels))
            MOBILE_NAVIGATION_ENGLISH_TEXT_SIZE_RATIO else MOBILE_NAVIGATION_TEXT_SIZE_RATIO
        return scaledHeight(ResUtil.getScreenHeight(context), ratio)
    }

    @JvmStatic
    fun getMobileHomeFabSizePixels(context: android.content.Context): Int {
        return scaledHeight(ResUtil.getScreenHeight(context), MOBILE_HOME_FAB_SIZE_RATIO)
    }

    @JvmStatic
    fun getMobileHomeFabIconSizePixels(context: android.content.Context): Int {
        return scaledHeight(ResUtil.getScreenHeight(context), MOBILE_HOME_FAB_ICON_RATIO)
    }

    @JvmStatic
    fun getMobileHomeFabMarginPixels(context: android.content.Context): Int {
        return scaledHeight(ResUtil.getScreenHeight(context), MOBILE_HOME_FAB_MARGIN_RATIO)
    }

    @JvmStatic
    fun getMobilePortraitTitleMaxWidthPixels(context: android.content.Context): Int {
        return scaledWidth(ResUtil.getScreenWidth(context), MOBILE_PORTRAIT_TITLE_MAX_WIDTH_RATIO)
    }

    @JvmStatic
    fun getMobilePortraitTitleTextSizePixels(context: android.content.Context): Int {
        return Math.round(TextScale.pixels(context, com.wuming.tv.core.design.TextRole.HEADLINE))
    }

    @JvmStatic
    fun getMobilePortraitFabSizePixels(context: android.content.Context): Int {
        return scaledWidth(ResUtil.getScreenWidth(context), MOBILE_PORTRAIT_FAB_SIZE_RATIO)
    }

    @JvmStatic
    fun getMobilePortraitFabIconSizePixels(context: android.content.Context): Int {
        return scaledWidth(ResUtil.getScreenWidth(context), MOBILE_PORTRAIT_FAB_ICON_RATIO)
    }

    @JvmStatic
    fun getMobilePortraitFabMarginPixels(context: android.content.Context): Int {
        return scaledWidth(ResUtil.getScreenWidth(context), MOBILE_PORTRAIT_FAB_MARGIN_RATIO)
    }

    @JvmStatic
    fun getMobileToolbarActionMarginPixels(toolbarHeight: Int): Int {
        return getTypeChipSideMarginPixels(toolbarHeight)
    }

    @JvmStatic
    fun scaledHeight(screenHeight: Int, ratio: Float): Int {
        return Math.max(1, Math.round(Math.max(1, screenHeight) * ratio))
    }

    @JvmStatic
    fun scaledWidth(screenWidth: Int, ratio: Float): Int {
        return Math.max(1, Math.round(Math.max(1, screenWidth) * ratio))
    }

    // ===== Responsive dialogs =====

    @JvmField
    val COMPACT_DIALOG_LANDSCAPE_WIDTH_RATIO = 0.4f
    @JvmField
    val COMPACT_DIALOG_PORTRAIT_WIDTH_RATIO = 0.9f
    @JvmField
    val LIST_DIALOG_LANDSCAPE_WIDTH_RATIO = 0.5f
    @JvmField
    val LIST_DIALOG_PORTRAIT_WIDTH_RATIO = 0.9f
    @JvmField
    val DOH_DIALOG_WIDTH_RATIO = 0.4f
    @JvmField
    val WEB_DIALOG_WIDTH_RATIO = 0.8f
    @JvmField
    val WEB_DIALOG_HEIGHT_RATIO = 0.8f
    @JvmField
    val HISTORY_DIALOG_COMPACT_WIDTH_RATIO = 0.5f
    @JvmField
    val HISTORY_DIALOG_FULL_WIDTH_RATIO = 0.8f
    @JvmField
    val SITE_RANKING_DIALOG_WIDTH_RATIO = 0.85f
    @JvmField
    val TV_CONFIG_DIALOG_WIDTH_RATIO = 0.55f
    @JvmField
    val TV_CONFIG_HELP_DIALOG_WIDTH_RATIO = 0.65f

    private const val MOBILE_SITE_DIALOG_SINGLE_COLUMN_WIDTH_RATIO = 0.9f
    private const val MOBILE_SITE_DIALOG_BASE_GRID_WIDTH_RATIO = 0.48f
    private const val MOBILE_SITE_DIALOG_COLUMN_WIDTH_STEP_RATIO = 0.10f
    private const val MOBILE_SITE_DIALOG_MAX_WIDTH_RATIO = 0.95f
    private const val TV_SITE_DIALOG_BASE_WIDTH_RATIO = 0.46f
    private const val TV_SITE_DIALOG_COLUMN_WIDTH_STEP_RATIO = 0.12f
    private const val TV_SITE_DIALOG_MAX_WIDTH_RATIO = 0.95f
    private const val SITE_DIALOG_MAX_HEIGHT_RATIO = 0.52f
    private const val SITE_DIALOG_TEXT_HORIZONTAL_PADDING_RATIO = 0.14f

    /**
     * Returns a dialog width that remains compact on landscape/TV screens but
     * uses the available width on a narrow portrait phone.
     */
    @JvmStatic
    fun getResponsiveDialogWidth(
        context: android.content.Context,
        landscapeRatio: Float,
        portraitRatio: Float
    ): Int {
        return responsiveDialogWidth(
            ResUtil.getScreenWidth(context),
            ResUtil.isLand(context),
            landscapeRatio,
            portraitRatio
        )
    }

    @JvmStatic
    fun responsiveDialogWidth(
        screenWidth: Int,
        landscape: Boolean,
        landscapeRatio: Float,
        portraitRatio: Float
    ): Int {
        if (screenWidth <= 0) return 0
        val ratio = if (landscape) landscapeRatio else portraitRatio
        return Math.max(1, Math.round(screenWidth * ratio))
    }

    @JvmStatic
    fun getScreenWidthPixels(ratio: Float): Int {
        return scaledWidth(ResUtil.getScreenWidth(), ratio)
    }

    @JvmStatic
    fun getScreenHeightPixels(ratio: Float): Int {
        return scaledHeight(ResUtil.getScreenHeight(), ratio)
    }

    @JvmStatic
    fun getMobileSiteDialogWidthRatio(spanCount: Int): Float {
        if (spanCount <= 1) return MOBILE_SITE_DIALOG_SINGLE_COLUMN_WIDTH_RATIO
        val ratio = MOBILE_SITE_DIALOG_BASE_GRID_WIDTH_RATIO +
                (spanCount - 1) * MOBILE_SITE_DIALOG_COLUMN_WIDTH_STEP_RATIO
        return Math.min(MOBILE_SITE_DIALOG_MAX_WIDTH_RATIO, ratio)
    }

    @JvmStatic
    fun getTvSiteDialogWidthRatio(spanCount: Int): Float {
        val ratio = TV_SITE_DIALOG_BASE_WIDTH_RATIO +
                (Math.max(1, spanCount) - 1) * TV_SITE_DIALOG_COLUMN_WIDTH_STEP_RATIO
        return Math.min(TV_SITE_DIALOG_MAX_WIDTH_RATIO, ratio)
    }

    @JvmStatic
    fun getSiteDialogMaxHeightPixels(): Int {
        return getScreenHeightPixels(SITE_DIALOG_MAX_HEIGHT_RATIO)
    }

    @JvmStatic
    fun getSiteDialogItemHeightPixels(context: android.content.Context): Int {
        return ControlSpacing.dimensionPixels(context, R.dimen.ds_control_min_height)
    }

    @JvmStatic
    fun getSiteDialogTextHorizontalPaddingPixels(itemHeight: Int): Int {
        return Math.max(0, Math.round(itemHeight * SITE_DIALOG_TEXT_HORIZONTAL_PADDING_RATIO))
    }

    // ===== Leanback home header =====

    private const val TV_HOME_TOOLBAR_HEIGHT_RATIO = 0.125f
    private const val TV_HOME_TOOLBAR_ICON_RATIO = 0.45f

    @JvmStatic
    fun getTvHomeToolbarHeightPixels(): Int {
        return getScreenHeightPixels(TV_HOME_TOOLBAR_HEIGHT_RATIO)
    }

    @JvmStatic
    fun getTvHomeToolbarIconSizePixels(toolbarHeight: Int): Int {
        return Math.max(1, Math.round(toolbarHeight * TV_HOME_TOOLBAR_ICON_RATIO))
    }

    // ===== Two-Column Layout: Left Column Width =====

    /**
     * Width ratio for the left column (site list) in two-column layouts.
     * Applied to: FastSearchActivity (vertical mode), CollectFragment
     * XML affected: llWord, llCollect (layout_width is overridden)
     */
    @JvmField
    val SEARCH_SITE_COLUMN_PORTRAIT_WIDTH_RATIO = 0.22f
    @JvmField
    val SEARCH_SITE_COLUMN_LANDSCAPE_WIDTH_RATIO = 0.14f

    // Both search routes resolve LABEL typography; only their geometry adapts.
    @JvmField
    val MOBILE_SEARCH_SITE_ITEM_HEIGHT_RATIO = 0.064f
    @JvmField
    val MOBILE_SEARCH_SITE_STRIP_HEIGHT_RATIO = 0.09f

    /**
     * Returns a compact, orientation-aware site column. Portrait needs enough room for
     * two useful poster columns; landscape uses a slim rail and spends the width on results.
     * Applied to: FastSearchActivity (vertical mode), CollectFragment, Product.getCollectSpec()
     */
    @JvmStatic
    fun getSiteColumnWidthPixels(context: android.content.Context): Int {
        val width = ResUtil.getScreenWidth(context)
        val ratio = if (ResUtil.isLand(context))
            SEARCH_SITE_COLUMN_LANDSCAPE_WIDTH_RATIO
        else
            SEARCH_SITE_COLUMN_PORTRAIT_WIDTH_RATIO
        return Math.max(1, Math.round(width * ratio))
    }

    /**
     * Width for the site rail inside its column container. The container's layout width
     * already includes its horizontal padding, so handing the child the full column width
     * pushes the item's trailing padding and rounded corner past the container clip.
     * Phones keep the previous full-width behaviour untouched.
     */
    @JvmStatic
    fun getSiteRailWidthPixels(
        context: android.content.Context,
        container: android.view.View,
    ): Int {
        val column = getSiteColumnWidthPixels(context)
        if (!isPadViewport()) return column
        return Math.max(1, column - container.paddingStart - container.paddingEnd)
    }

    @JvmStatic
    fun getMobileSearchTitleTextSizePixels(context: android.content.Context): Int {
        return Math.round(TextScale.pixels(context, com.wuming.tv.core.design.TextRole.HEADLINE))
    }

    @JvmStatic
    fun getMobileSearchNameTextSizePixels(context: android.content.Context): Int {
        return Math.round(TextScale.pixels(context, com.wuming.tv.core.design.TextRole.LABEL))
    }

    @JvmStatic
    fun getMobileSearchBadgeTextSizePixels(context: android.content.Context): Int {
        return Math.round(TextScale.pixels(context, com.wuming.tv.core.design.TextRole.LABEL))
    }

    @JvmStatic
    fun getMobileSearchWordTextSizePixels(context: android.content.Context): Int {
        return Math.round(TextScale.pixels(context, com.wuming.tv.core.design.TextRole.LABEL))
    }

    @JvmStatic
    fun getMobileSearchSiteTextSizePixels(context: android.content.Context): Int {
        return Math.round(TextScale.pixels(context, com.wuming.tv.core.design.TextRole.LABEL))
    }

    private fun isPadViewport(): Boolean = !Util.isLeanback() && ResUtil.isPad()

    @JvmStatic
    fun getMobileSearchSiteItemHeightPixels(context: android.content.Context): Int {
        return scaleShortEdge(context, MOBILE_SEARCH_SITE_ITEM_HEIGHT_RATIO)
    }

    @JvmStatic
    fun getMobileSearchSiteStripHeightPixels(context: android.content.Context): Int {
        return scaleShortEdge(context, MOBILE_SEARCH_SITE_STRIP_HEIGHT_RATIO)
    }

    @JvmStatic
    fun scaleShortEdge(context: android.content.Context, ratio: Float): Int {
        // Always scale against the real short edge so the ratio is width-dependent and
        // rotation-stable (short edge is identical in portrait and landscape). No device
        // class gate: sizing is purely a function of the screen's stable width.
        val shortEdge = Math.min(ResUtil.getScreenWidth(context), ResUtil.getScreenHeight(context))
        return Math.max(1, Math.round(shortEdge * ratio))
    }

    private const val DEFAULT_SUBTITLE_TEXT_SHORT_EDGE_RATIO = 0.033f

    @JvmStatic
    fun getDefaultSubtitleTextSizePixels(context: android.content.Context): Int {
        return scaleShortEdge(context, DEFAULT_SUBTITLE_TEXT_SHORT_EDGE_RATIO)
    }

    // ===== Two-Column Layout: Site List (Horizontal Mode) Height =====

    /**
     * Height ratio for horizontal site list at top (compact mode).
     * Applied to: FastSearchActivity (siteListLayout == 2)
     */
    @JvmField
    val SITE_LIST_COMPACT_HEIGHT_RATIO = 0.07f

    /**
     * Height ratio for horizontal site list at top (normal mode).
     * Applied to: FastSearchActivity (siteListLayout == 1)
     */
    @JvmField
    val SITE_LIST_HORIZONTAL_HEIGHT_RATIO = 0.09f

    /** Compact TV fast-search site chip height, scaled from a 1920 px reference. */
    @JvmStatic
    fun getFastSearchSiteItemHeightPixels(): Int {
        return ViewportScale.scaleTvPx(64f)
    }

    /** Row height leaves room for the Leanback focus zoom without clipping its outline. */
    @JvmStatic
    fun getTvSearchFilterRowHeightPixels(): Int {
        return ViewportScale.scaleTvPx(84f)
    }

    /** Shared title size for all TV search-result screens. */
    @JvmStatic
    fun getTvSearchTitleTextSizePixels(): Int {
        return Math.round(TextScale.pixels(App.get(), com.wuming.tv.core.design.TextRole.HEADLINE))
    }

    /** Compact TV fast-search site chip text size, returned in pixels. */
    @JvmStatic
    fun getFastSearchSiteTextSizePixels(): Int {
        return Math.round(TextScale.pixels(App.get(), com.wuming.tv.core.design.TextRole.LABEL))
    }

    /** Horizontal padding inside a TV fast-search site chip. */
    @JvmStatic
    fun getFastSearchSiteHorizontalPaddingPixels(): Int {
        return ViewportScale.scaleTvPx(8f)
    }

    /** Gap between TV fast-search site chips. */
    @JvmStatic
    fun getFastSearchSiteMarginPixels(): Int {
        return ViewportScale.scaleTvPx(12f)
    }

    /**
     * Applies the same chip geometry to both Leanback search implementations.
     */
    @JvmStatic
    fun styleTvSearchFilterChip(view: android.widget.TextView, targetHeight: Int) {
        val defaultHeight = getFastSearchSiteItemHeightPixels()
        val itemHeight = if (targetHeight > 0) targetHeight else defaultHeight
        val scale = itemHeight.toFloat() / defaultHeight
        val horizontalPadding = Math.round(getFastSearchSiteHorizontalPaddingPixels() * scale)

        TextScale.bind(view)
        view.setGravity(android.view.Gravity.CENTER)
        view.setTextAlignment(android.view.View.TEXT_ALIGNMENT_CENTER)
        view.setIncludeFontPadding(false)
        view.setSingleLine(true)
        view.setMinWidth(itemHeight * 2)
        view.setPadding(horizontalPadding, 0, horizontalPadding, 0)

        val params = view.getLayoutParams()
        val layoutParams = if (params == null) {
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                itemHeight
            )
        } else {
            params.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            params.height = itemHeight
            params
        }
        if (layoutParams is android.view.ViewGroup.MarginLayoutParams) {
            layoutParams.setMarginEnd(Math.round(getFastSearchSiteMarginPixels() * scale))
        }
        view.setLayoutParams(layoutParams)
    }

    // ===== Leanback configuration dialog =====

    // Geometry mirrors design-space dp in dialog_config.xml and uses scaleTvDp.
    // Text is resolved separately through semantic typography, including system font scale.
    private const val TV_CONFIG_QR_SIZE_DESIGN_DP = 180f
    private const val TV_CONFIG_CONTENT_HEIGHT_DESIGN_DP = 214f
    private const val TV_CONFIG_OUTER_PADDING_DESIGN_DP = 16f
    private const val TV_CONFIG_CONTENT_MARGIN_DESIGN_DP = 12f
    private const val TV_CONFIG_INNER_PADDING_DESIGN_DP = 4f
    private const val TV_CONFIG_DIVIDER_DESIGN_DP = 2f
    private const val TV_CONFIG_DIVIDER_MARGIN_DESIGN_DP = 10f

    @JvmStatic
    fun getTvConfigQrSizePixels(): Int {
        return ViewportScale.scaleTvDp(TV_CONFIG_QR_SIZE_DESIGN_DP)
    }

    @JvmStatic
    fun getTvConfigContentHeightPixels(): Int {
        return ViewportScale.scaleTvDp(TV_CONFIG_CONTENT_HEIGHT_DESIGN_DP)
    }

    @JvmStatic
    fun getTvConfigContentTextSizePixels(): Int {
        return Math.round(TextScale.pixels(App.get(), com.wuming.tv.core.design.TextRole.BODY))
    }

    @JvmStatic
    fun getTvConfigButtonTextSizePixels(): Int {
        return Math.round(TextScale.pixels(App.get(), com.wuming.tv.core.design.TextRole.LABEL))
    }

    @JvmStatic
    fun getTvConfigHintTextSizePixels(): Int {
        return Math.round(TextScale.pixels(App.get(), com.wuming.tv.core.design.TextRole.CAPTION))
    }

    @JvmStatic
    fun getTvConfigOuterPaddingPixels(): Int {
        return ViewportScale.scaleTvDp(TV_CONFIG_OUTER_PADDING_DESIGN_DP)
    }

    @JvmStatic
    fun getTvConfigContentMarginPixels(): Int {
        return ViewportScale.scaleTvDp(TV_CONFIG_CONTENT_MARGIN_DESIGN_DP)
    }

    @JvmStatic
    fun getTvConfigInnerPaddingPixels(): Int {
        return ViewportScale.scaleTvDp(TV_CONFIG_INNER_PADDING_DESIGN_DP)
    }

    @JvmStatic
    fun getTvConfigDividerSizePixels(): Int {
        return ViewportScale.scaleTvDp(TV_CONFIG_DIVIDER_DESIGN_DP)
    }

    @JvmStatic
    fun getTvConfigDividerMarginPixels(): Int {
        return ViewportScale.scaleTvDp(TV_CONFIG_DIVIDER_MARGIN_DESIGN_DP)
    }

    // ===== Video Item Aspect Ratio =====

    /**
     * REGRESSION GUARDRAIL:
     * Read .codebuddy/lessons/05-regression-prevention-playbook.md before
     * changing any value in this section. These constants are shared contracts,
     * not page-specific tuning knobs. Do not override them in an Activity,
     * Fragment, adapter, provider Style, or orientation resource.
     *
     * The single poster contract for every mobile and Leanback grid.
     * Semantics are always width / height, so 0.75 produces a 3:4 portrait poster.
     */
    @JvmField
    val VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO = LayoutPolicy.POSTER_WIDTH_TO_HEIGHT

    /*
     * Compatibility aliases. Screen orientation and provider style must never
     * change poster geometry, so every alias intentionally has the same value.
     */
    @JvmField
    val VIDEO_THUMB_RATIO_WIDTH_OVER_HEIGHT = VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO
    @JvmField
    val VIDEO_IMAGE_ASPECT_RATIO_PORTRAIT = VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO
    @JvmField
    val VIDEO_IMAGE_ASPECT_RATIO_LANDSCAPE = VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO
    @JvmField
    val VIDEO_IMAGE_ASPECT_RATIO_SQUARE = VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO
    @JvmField
    val VIDEO_IMAGE_ASPECT_RATIO = VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO

    // ===== Grid Column Count =====

    /**
     * REGRESSION GUARDRAIL:
     * Default grid rhythm is mobile portrait=3, mobile landscape=5, Leanback=5.
     * Keep home/category/search/collection code routed through these constants.
     * If product requirements change, update the constants, tests, playbook,
     * and device screenshots together—never patch a single page in isolation.
     *
     * Number of columns in the video grid for search results (leanback/TV).
     * Applied to: FastSearchActivity (leanback)
     */
    @JvmField
    val SEARCH_GRID_SPAN_COUNT_LEANBACK = LayoutPolicy.TV_COLUMNS

    /**
     * Mobile landscape uses the same balanced five-poster rhythm as Leanback.
     * Keep this as the single source of truth for home, category, search, and
     * collection grids so individual pages cannot drift back to 4/6 columns.
     */
    @JvmField
    val VIDEO_GRID_SPAN_COUNT_MOBILE_LANDSCAPE = LayoutPolicy.MOBILE_LANDSCAPE_COLUMNS

    /**
     * Three posters give phone portrait layouts readable artwork and titles,
     * and match the existing portrait search/collection grid.
     */
    @JvmField
    val VIDEO_GRID_SPAN_COUNT_MOBILE_PORTRAIT = LayoutPolicy.MOBILE_PORTRAIT_COLUMNS

    /**
     * Returns the orientation-aware grid column count for two-column and full-width search pages.
     * Portrait: 3 columns beside the compact provider rail. Landscape: 5 columns.
     * Applied to: FastSearchActivity, CollectFragment — both GridLayoutManager span count and
     * item size calculation via Product.getSpecWithReservedWidth().
     */
    @JvmStatic
    fun getSearchGridSpanCount(context: android.content.Context): Int {
        return if (ResUtil.isLand(context))
            VIDEO_GRID_SPAN_COUNT_MOBILE_LANDSCAPE
        else
            VIDEO_GRID_SPAN_COUNT_MOBILE_PORTRAIT
    }
}
