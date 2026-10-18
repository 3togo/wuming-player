package com.wuming.android.tv.setting;

import org.junit.Test;
import static org.junit.Assert.*;

public class DlnaSettingsTest {
    @Test public void blankNamesRestoreThePrivateTvDefault() {
        assertEquals("_私人电视-非公共投屏设备-请勿连接或投屏-正在使用中", DlnaSettings.normalize(null));
        assertEquals(DlnaSettings.DEFAULT_NAME, DlnaSettings.normalize(" \n\t "));
    }
    @Test public void namesPreserveUnicodeAndRemoveControlCharacters() {
        assertEquals("我的电视 📺", DlnaSettings.normalize(" 我的电视 📺\n\u0000 "));
        assertEquals("TV", DlnaSettings.normalize("TV\ud800\ufffe"));
    }
    @Test public void longNamesNeverSplitSurrogatePairs() {
        String result = DlnaSettings.normalize("📺".repeat(90));
        assertEquals(80, result.codePointCount(0,result.length()));
        assertEquals("📺".repeat(80), result);
    }
}
