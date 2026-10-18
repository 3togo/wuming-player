package com.wuming.tv.core.design

import org.junit.Assert.*
import org.junit.Test

class TypographyPolicyTest {
    @Test fun tabletEnlargementIsBoundedAndIndependentOfOrientation() {
        assertEquals(1f, TypographyPolicy.tabletScale(360f), 0f)
        assertEquals(1f, TypographyPolicy.tabletScale(240f), 0f)
        assertEquals(1.25f, TypographyPolicy.tabletScale(2560f), 0f)
        assertEquals(1.1486486f, TypographyPolicy.tabletScale(800f), 0.00001f)
        // Both portrait and landscape adapters supply the same native smallest width.
        assertEquals(TypographyPolicy.tabletScale(minOf(800f, 1280f)),
            TypographyPolicy.tabletScale(minOf(1280f, 800f)), 0f)
    }

    @Test fun malformedPreferencesCannotShrinkOrExplodeText() {
        assertEquals(1f, TypographyPolicy.tabletScale(Float.NaN), 0f)
        assertEquals(1.25f, TypographyPolicy.tabletScale(2000f, Float.NaN, Float.NaN), 0f)
        assertEquals(1.5f, TypographyPolicy.tabletScale(2000f, 1100f, 100f), 0f)
        assertEquals(1f, TypographyPolicy.tabletScale(2000f, 0f, -10f), 0f)
    }

    @Test fun semanticHierarchyAndTvDistanceSizingRemainConsistent() {
        for (tv in listOf(false, true)) {
            assertTrue(TypographyPolicy.logicalSize(TextRole.HEADLINE, tv) > TypographyPolicy.logicalSize(TextRole.BODY, tv))
            assertTrue(TypographyPolicy.logicalSize(TextRole.BODY, tv) > TypographyPolicy.logicalSize(TextRole.LABEL, tv))
            assertTrue(TypographyPolicy.logicalSize(TextRole.LABEL, tv) >= TypographyPolicy.logicalSize(TextRole.CAPTION, tv))
        }
        assertEquals(14f, TypographyPolicy.logicalSize(TextRole.LABEL), 0f)
        assertEquals(16f, TypographyPolicy.logicalSize(TextRole.LABEL, true), 0f)
        assertEquals(TextRole.LABEL, TextRole.fromName("label"))
        assertNull(TextRole.fromName("unknown"))
    }
}
