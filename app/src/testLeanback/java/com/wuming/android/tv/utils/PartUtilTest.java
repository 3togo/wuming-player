package com.wuming.android.tv.utils;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

public class PartUtilTest {
    @Test public void originalTitleStaysFirstAndDuplicatePartsAreRemoved() {
        assertEquals(List.of("星河：星河：远方", "星河", "远方"), PartUtil.split("星河：星河：远方"));
    }

    @Test public void seasonYearAndParenthesisProduceBaseTitle() {
        assertEquals(List.of("星河第二季", "星河"), PartUtil.split("星河第二季"));
        assertEquals(List.of("星河2024", "星河"), PartUtil.split("星河2024"));
        assertEquals(List.of("星河(国语)", "星河"), PartUtil.split("星河(国语)"));
    }

    @Test public void colonTakesPrecedenceAndPartsUseTheirFirstWord() {
        assertEquals(List.of("星河第二季：远方 世界", "星河第二季", "远方"), PartUtil.split("星河第二季：远方 世界"));
    }

    @Test public void javaWhitespaceAndEmptyTitleBehaviorRemainIntact() {
        assertEquals(List.of("  星河  ", "星河"), PartUtil.split("  星河  "));
        assertEquals(List.of(""), PartUtil.split(""));
        assertEquals(List.of("星河\u00a0：远方", "星河\u00a0", "远方"), PartUtil.split("星河\u00a0：远方"));
    }
}
