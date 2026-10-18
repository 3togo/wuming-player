package com.wuming.android.tv.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class TitleNormalizerTest {

    @Test
    public void traditionalConversionDoesNotDependOnDeviceLocale() {
        assertEquals("无间道", TitleNormalizer.simplified("無間道"));
        assertTrue(TitleNormalizer.equivalent("無間道", "无间道"));
        assertEquals("无间道", TitleNormalizer.key(" 無 間 道 "));
    }

    @Test
    public void normalizationHandlesWidthCaseAndPunctuation() {
        assertEquals("the matrix 1999", TitleNormalizer.clean("ＴＨＥ Matrix：1999"));
    }

    @Test
    public void mixedScriptSegmentsRemainMeaningful() {
        assertEquals(List.of("無間道", "Infernal", "Affairs"),
                TitleNormalizer.scriptSegments("無間道 / Infernal Affairs"));
    }
}
