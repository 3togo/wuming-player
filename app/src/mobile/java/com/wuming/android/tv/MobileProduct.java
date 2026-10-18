package com.wuming.android.tv;
import com.wuming.android.tv.setting.DisplaySettings;

import android.content.Context;

import com.wuming.android.tv.bean.Style;
import com.wuming.android.tv.utils.FontScale;
import com.wuming.android.tv.utils.GridSpecCalculator;
import com.wuming.android.tv.utils.LayoutConfig;
import com.wuming.android.tv.utils.ResUtil;

/**
 * Unified grid layout calculator — single source of truth for all grid spacing.
 *
 * LESSON LEARNED — DO NOT BREAK THESE RULES:
 *   1. ONE private method (computeSpec) handles ALL grids. Every public API delegates to it.
 *      Never add a second calculation path — duplication guarantees inconsistency.
 *   2. GAP computed from SCREEN WIDTH (not item width, not smallest dimension, not dp).
 *      Screen-width-based ratio is the only approach that produces visually consistent
 *      spacing across all pages, column counts, and orientations.
 *      - dp/fixed pixels: gap looks different on narrow vs wide items (different column counts).
 *      - item-width ratio: gap is consistent within an orientation, but differs between
 *        portrait and landscape because item widths change.
 *      - smallest-dimension: gap is too small in landscape (same px on a much wider screen).
 *      - screen-width ratio: CORRECT. Gap scales with the visible width, looks natural
 *        regardless of orientation or column count.
 *   3. No XML padding on RecyclerView containers — all spacing comes from item margins
 *      computed here. Adding padding in XML would create a second source of truth.
 *   4. GAP_SCREEN_RATIO and OUTER_SCREEN_RATIO must be equal — the visual gap between
 *      items should match the gap from screen edge to first/last item.
 *   5. When adding a new grid page, ALWAYS route through computeSpec(). Never ad-hoc.
 *
 * Formula:
 *   gap       = screenWidth * GAP_SCREEN_RATIO
 *   outer     = screenWidth * OUTER_SCREEN_RATIO
 *   available = gridAreaWidth - outer - extraSpace
 *   width     = (available - gap * (columns - 1)) / columns
 *   margin    = gap / 2  (per-side margin; two adjacent items sum to the full gap)
 */
public class MobileProduct extends ProductCore {

    static {
        ProductCore.setInstance(new MobileProduct());
    }

    /* (non-static implementation of shared queries) */
    @Override protected int deviceTypeImpl() { return 1; }
    @Override protected int nameTextSizeImpl() { return FontScale.vodName(); }
    @Override protected int badgeTextSizeImpl() { return FontScale.vodBadge(); }

    /**
     * Gap is computed as a percentage of the current screen width so it scales proportionally
     * with the visible width. This ensures the gap looks visually the same in both orientations.
     * 2.2% of screen width gives ~24px on a 1080px portrait screen, ~53px on a 2400px landscape screen.
     */
    private static final float GAP_SCREEN_RATIO = 0.022f;

    /**
     * Outer padding: same ratio as gap for consistent edge spacing.
     */
    private static final float OUTER_SCREEN_RATIO = GAP_SCREEN_RATIO;

    // ==================== Column Count ====================

    /**
     * REGRESSION GUARDRAIL:
     * Do not put literal span counts in mobile screens. All full-width grids
     * must call this method; search/collection use LayoutConfig's matching
     * orientation-aware method. See the regression-prevention playbook.
     *
     * Simple, stable column count for full-width grid pages.
     * Mobile portrait uses 3 columns for readable artwork and titles.
     * All mobile landscape layouts use the same balanced 5-column grid as Leanback.
     */
    public static int getColumn(Context context) {
        boolean isLand = ResUtil.isLand(context);
        int offset = Math.max(0, DisplaySettings.getSize() - 2);
        int base = isLand
                ? LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_LANDSCAPE
                : LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_PORTRAIT;
        return Math.max(1, base + offset);
    }

    /**
     * REGRESSION GUARDRAIL: provider Style must not change grid geometry.
     * Posters are always 3:4, so reducing columns for style.isLand() would make
     * the same page jump between layouts depending on provider metadata.
     */
    public static int getColumn(Context context, Style style) {
        return getColumn(context);
    }

    // ==================== Grid Spec Calculations ====================

    /**
     * Full-width grid spec (front page, no left column).
     * Returns [width, height, margin, imageHeight].
     */
    public static int[] getSpec(Context context) {
        return getSpec(context, Style.rect());
    }

    /**
     * Full-width grid spec with custom style.
     * Returns [width, height, margin, imageHeight].
     */
    public static int[] getSpec(Context context, Style style) {
        return getSpec(context, style, ResUtil.getScreenWidth(context));
    }

    /**
     * Grid spec constrained to the measured content viewport. This is required
     * on the landscape home screen, where the navigation rail consumes part of
     * the physical screen width.
     */
    public static int[] getSpec(Context context, Style style, int availableWidth) {
        int columns = getColumn(context, style);
        int extraSpace = style.isOval()
                ? ResUtil.getDimensionPixelSize(R.dimen.vs_16) * columns
                : 0;
        float ratio = resolveRatio(style);
        int screenWidth = ResUtil.getScreenWidth(context);
        int reservedWidth = Math.max(0, screenWidth - Math.max(1, availableWidth));
        return computeSpec(context, reservedWidth, columns, extraSpace, ratio);
    }

    /**
     * Two-column layout spec (left site column + right icon grid).
     * Returns [width, height, margin, imageHeight].
     */
    public static int[] getSpecWithLeftColumn(Context context, int leftColumnPixels) {
        int columns = LayoutConfig.getSearchGridSpanCount(context);
        return computeSpec(context, leftColumnPixels, columns, 0,
                LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO);
    }

    /**
     * Core grid-item computation: given reserved left width and column count.
     * Returns [width, height, margin, imageHeight].
     */
    public static int[] getSpecWithReservedWidth(Context context, int reservedLeftPixels, int columns) {
        return computeSpec(context, reservedLeftPixels, columns, 0,
                LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO);
    }

    /**
     * Grid spec based on the RecyclerView's measured content viewport.
     *
     * <p>Use this for every grid that shares the screen with a navigation rail,
     * provider column, system inset, or toolbar. Estimating those reserved
     * widths from the physical screen makes fixed-width children overlap their
     * margins and produces page-specific gaps.</p>
     */
    public static int[] getSpecForViewport(Context context, int viewportWidth, int columns) {
        int screenWidth = ResUtil.getScreenWidth(context);
        int reservedWidth = Math.max(0, screenWidth - Math.max(1, viewportWidth));
        return computeSpec(context, reservedWidth, columns, 0,
                LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO);
    }

    /**
     * Convenience: spec for collect/search pages with left column.
     */
    public static int[] getCollectSpec(Context context) {
        int leftColumnWidth = LayoutConfig.getSiteColumnWidthPixels(context);
        return getSpecWithLeftColumn(context, leftColumnWidth);
    }

    /**
     * Convenience: spec for full-width search pages (no left column).
     */
    public static int[] getSearchSpec(Context context) {
        return computeSpec(context, 0, LayoutConfig.getSearchGridSpanCount(context), 0,
                LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO);
    }

    // ==================== Internal Implementation ====================

    /**
     * Single unified calculation for ALL grid layouts.
     *
     * @param context        Android context
     * @param reservedWidth  Width reserved for left column (0 for full-width grids)
     * @param columns        Number of grid columns
     * @param extraSpace     Extra space to subtract (for oval style, 0 otherwise)
     * @param aspectRatio    imageHeight = width / aspectRatio
     * @return int[]{width, height, margin, imageHeight}
     *         width       — item width in pixels
     *         height      — total item height (image + name text area) in pixels
     *         margin      — per-side margin in pixels (two adjacent items sum to the full gap)
     *         imageHeight — image-only height in pixels (for adapters that set image height separately)
     */
    private static int[] computeSpec(Context context, int reservedWidth, int columns,
                                      int extraSpace, float aspectRatio) {
        // REGRESSION GUARDRAIL: this is the only mobile poster-size calculation
        // path. Do not duplicate its formula in an adapter, Activity, or XML.
        int screenWidth = ResUtil.getScreenWidth(context);
        int nameTextHeight = estimateNameTextHeight(context);
        return GridSpecCalculator.calculate(screenWidth, reservedWidth, columns, extraSpace, aspectRatio,
                nameTextHeight, GAP_SCREEN_RATIO, OUTER_SCREEN_RATIO);
    }

    /**
     * Estimates the height of the name text area below the thumbnail.
     * This includes up to 2 lines of text (maxLines=2) plus vertical padding.
     * Uses the same short-edge-based font size that mobile search adapters apply.
     */
    private static int estimateNameTextHeight(Context context) {
        int textSizePx = LayoutConfig.getMobileSearchNameTextSizePixels(context);
        // Two compact lines with no hyphenation. The layouts use 6 dp top and
        // bottom padding so the title band stays subordinate to the poster.
        int twoLineHeight = (int) (textSizePx * 2 * 1.25f);
        int verticalPadding = ResUtil.getDimensionPixelSize(R.dimen.vs_14);
        return twoLineHeight + verticalPadding;
    }

    private static float resolveRatio(Style style) {
        return LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO;
    }

}
