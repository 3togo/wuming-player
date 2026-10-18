package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LayoutConfigTest {

    @Test
    public void searchSiteColumnIsNarrowerInLandscape() {
        assertEquals(0.22f, LayoutConfig.SEARCH_SITE_COLUMN_PORTRAIT_WIDTH_RATIO, 0.0f);
        assertEquals(0.14f, LayoutConfig.SEARCH_SITE_COLUMN_LANDSCAPE_WIDTH_RATIO, 0.0f);
        assertTrue(LayoutConfig.SEARCH_SITE_COLUMN_LANDSCAPE_WIDTH_RATIO
                < LayoutConfig.SEARCH_SITE_COLUMN_PORTRAIT_WIDTH_RATIO);
    }

    @Test
    public void siteListCompactHeightRatio() {
        assertEquals(0.07f, LayoutConfig.SITE_LIST_COMPACT_HEIGHT_RATIO, 0.0f);
    }

    @Test
    public void siteListHorizontalHeightRatio() {
        assertEquals(0.09f, LayoutConfig.SITE_LIST_HORIZONTAL_HEIGHT_RATIO, 0.0f);
    }

    @Test
    public void videoThumbRatioWidthOverHeight() {
        assertEquals(3.0f / 4.0f, LayoutConfig.VIDEO_THUMB_RATIO_WIDTH_OVER_HEIGHT, 0.0f);
    }

    @Test
    public void videoImageAspectRatioPortrait() {
        assertEquals(3.0f / 4.0f, LayoutConfig.VIDEO_IMAGE_ASPECT_RATIO_PORTRAIT, 0.0f);
    }

    @Test
    public void videoImageAspectRatioLandscape() {
        assertEquals(3.0f / 4.0f, LayoutConfig.VIDEO_IMAGE_ASPECT_RATIO_LANDSCAPE, 0.0f);
    }

    @Test
    public void videoImageAspectRatioSquare() {
        assertEquals(3.0f / 4.0f, LayoutConfig.VIDEO_IMAGE_ASPECT_RATIO_SQUARE, 0.0f);
    }

    @Test
    public void videoImageAspectRatioDefaultsToPortrait() {
        assertEquals(LayoutConfig.VIDEO_IMAGE_ASPECT_RATIO_PORTRAIT,
                LayoutConfig.VIDEO_IMAGE_ASPECT_RATIO, 0.0f);
    }

    @Test
    public void searchGridSpanCountLeanback() {
        assertEquals(5, LayoutConfig.SEARCH_GRID_SPAN_COUNT_LEANBACK);
    }

    @Test
    public void mobileLandscapeMatchesLeanbackColumnCount() {
        assertEquals(5, LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_LANDSCAPE);
        assertEquals(LayoutConfig.SEARCH_GRID_SPAN_COUNT_LEANBACK,
                LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_LANDSCAPE);
    }

    @Test
    public void mobilePortraitUsesThreeColumns() {
        assertEquals(3, LayoutConfig.VIDEO_GRID_SPAN_COUNT_MOBILE_PORTRAIT);
    }

    @Test
    public void aspectRatiosAreConsistent() {
        assertEquals(LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO,
                LayoutConfig.VIDEO_IMAGE_ASPECT_RATIO_PORTRAIT, 0.0f);
        assertEquals(LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO,
                LayoutConfig.VIDEO_IMAGE_ASPECT_RATIO_LANDSCAPE, 0.0f);
        assertEquals(LayoutConfig.VIDEO_POSTER_WIDTH_TO_HEIGHT_RATIO,
                LayoutConfig.VIDEO_IMAGE_ASPECT_RATIO_SQUARE, 0.0f);
    }

    @Test
    public void portraitRatioProducesTallerThanWide() {
        assertTrue(LayoutConfig.VIDEO_IMAGE_ASPECT_RATIO_PORTRAIT < 1.0f);
    }

    @Test
    public void landscapeModeStillProducesTallerThanWide() {
        assertTrue(LayoutConfig.VIDEO_IMAGE_ASPECT_RATIO_LANDSCAPE < 1.0f);
    }

    @Test
    public void compactHeightIsLessThanNormalHeight() {
        assertTrue(LayoutConfig.SITE_LIST_COMPACT_HEIGHT_RATIO
                < LayoutConfig.SITE_LIST_HORIZONTAL_HEIGHT_RATIO);
    }

    @Test
    public void compactDialogUsesReadablePortraitWidth() {
        assertEquals(324, LayoutConfig.responsiveDialogWidth(360, false,
                LayoutConfig.COMPACT_DIALOG_LANDSCAPE_WIDTH_RATIO,
                LayoutConfig.COMPACT_DIALOG_PORTRAIT_WIDTH_RATIO));
        assertEquals(320, LayoutConfig.responsiveDialogWidth(800, true,
                LayoutConfig.COMPACT_DIALOG_LANDSCAPE_WIDTH_RATIO,
                LayoutConfig.COMPACT_DIALOG_PORTRAIT_WIDTH_RATIO));
    }

    @Test
    public void invalidViewportDoesNotProduceANegativeWidth() {
        assertEquals(0, LayoutConfig.responsiveDialogWidth(0, false, 0.4f, 0.9f));
    }

    @Test
    public void landscapeNavigationRailUsesTenPercentOfScreen() {
        assertEquals(204, LayoutConfig.mobileNavigationRailWidth(2400));
        assertEquals(92, LayoutConfig.mobileNavigationRailWidth(1080));
    }

    @Test
    public void landscapeHomeHeaderUsesCompactHeightBasedSizing() {
        int screenHeight = 1080;
        int toolbar = LayoutConfig.scaledHeight(screenHeight, LayoutConfig.MOBILE_HOME_TOOLBAR_HEIGHT_RATIO);
        int categories = LayoutConfig.scaledHeight(screenHeight, LayoutConfig.MOBILE_HOME_CATEGORY_ROW_HEIGHT_RATIO);
        int chip = LayoutConfig.scaledHeight(screenHeight, LayoutConfig.MOBILE_HOME_CATEGORY_CHIP_HEIGHT_RATIO);

        assertEquals(124, toolbar);
        assertEquals(97, categories);
        assertEquals(72, chip);
        assertTrue(toolbar + categories < screenHeight / 4);
        assertTrue(chip < categories);
    }

    @Test
    public void landscapeNavigationAndFabStayVisuallySecondary() {
        int screenHeight = 1080;
        int navItem = LayoutConfig.scaledHeight(screenHeight,
                LayoutConfig.MOBILE_NAVIGATION_ITEM_HEIGHT_RATIO);
        int navText = LayoutConfig.scaledHeight(screenHeight,
                LayoutConfig.MOBILE_NAVIGATION_TEXT_SIZE_RATIO);
        int fab = LayoutConfig.scaledHeight(screenHeight, LayoutConfig.MOBILE_HOME_FAB_SIZE_RATIO);
        int fabIcon = LayoutConfig.scaledHeight(screenHeight,
                LayoutConfig.MOBILE_HOME_FAB_ICON_RATIO);

        assertEquals(248, navItem);
        assertEquals(43, navText);
        assertEquals(29, LayoutConfig.scaledHeight(screenHeight,
                LayoutConfig.MOBILE_NAVIGATION_ENGLISH_TEXT_SIZE_RATIO));
        assertEquals(104, fab);
        assertEquals(44, fabIcon);
        assertTrue(navItem * 3 < screenHeight);
        assertTrue(fabIcon < fab / 2);
    }

    @Test
    public void portraitHeaderAndFabStaySecondaryToContent() {
        int screenWidth = 1080;

        assertEquals(497, LayoutConfig.scaledWidth(screenWidth,
                LayoutConfig.MOBILE_PORTRAIT_TITLE_MAX_WIDTH_RATIO));
        assertEquals(140, LayoutConfig.scaledWidth(screenWidth,
                LayoutConfig.MOBILE_PORTRAIT_FAB_SIZE_RATIO));
        assertEquals(59, LayoutConfig.scaledWidth(screenWidth,
                LayoutConfig.MOBILE_PORTRAIT_FAB_ICON_RATIO));
        assertEquals(27, LayoutConfig.scaledWidth(screenWidth,
                LayoutConfig.MOBILE_PORTRAIT_FAB_MARGIN_RATIO));
    }

    @Test
    public void siteDialogWidthsStayWithinPlatformBounds() {
        assertEquals(0.9f, LayoutConfig.getMobileSiteDialogWidthRatio(1), 0.0f);
        assertEquals(0.58f, LayoutConfig.getMobileSiteDialogWidthRatio(2), 0.000001f);
        assertEquals(0.68f, LayoutConfig.getMobileSiteDialogWidthRatio(3), 0.000001f);
        assertEquals(0.46f, LayoutConfig.getTvSiteDialogWidthRatio(1), 0.000001f);
        assertEquals(0.58f, LayoutConfig.getTvSiteDialogWidthRatio(2), 0.000001f);
        assertEquals(0.70f, LayoutConfig.getTvSiteDialogWidthRatio(3), 0.000001f);
    }

    @Test
    public void proportionalSpacingUsesNamedLayoutContracts() {
        assertEquals(15, LayoutConfig.getTypeChipSideMarginPixels(120));
        assertEquals(40, LayoutConfig.getTypeChipHorizontalPaddingPixels(120));
        assertEquals(8, LayoutConfig.getRankingVerticalPaddingPixels(16));
        assertEquals(1.0f,
                LayoutConfig.MOBILE_VIDEO_LANDSCAPE_PLAYER_WEIGHT
                        + LayoutConfig.MOBILE_VIDEO_LANDSCAPE_DETAIL_WEIGHT,
                0.0f);
    }

    @Test
    public void liveColumnWidthsAreClampedAndContentAware() {
        assertEquals(120, LayoutConfig.getLiveGroupWidthDp(0));
        assertEquals(172, LayoutConfig.getLiveGroupWidthDp(10));
        assertEquals(300, LayoutConfig.getLiveGroupWidthDp(100));
        assertEquals(344, LayoutConfig.getLiveChannelWidthDp(10));
        assertEquals(152, LayoutConfig.getLiveEpgWidthDp(10));
    }
}
