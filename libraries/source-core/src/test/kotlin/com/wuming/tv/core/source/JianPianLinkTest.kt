package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JianPianLinkTest {
    @Test
    fun routesOnlyJianPianLinksIntoTheNativeEngine() {
        assertTrue(JianPianLink.matches("tvbox-xg:xgplay://example.test/video"))
        assertTrue(JianPianLink.matches("jianpian://pathtype=url&path=ftp://example.test/video"))
        assertTrue(JianPianLink.matches("ftp://gbl.114s.example/video"))
        assertTrue(JianPianLink.matches("FTP://user@GBL.114S.example:21/video"))
        assertFalse(JianPianLink.matches("ftp://example.test/gbl.114s/video"))
        assertFalse(JianPianLink.matches("ftp://example.test/video?source=gbl.114s"))
        assertFalse(JianPianLink.matches("ftp://example.test/video"))
        assertFalse(JianPianLink.matches("ftp://notgbl.114s.example/video"))
        assertFalse(JianPianLink.matches("ftp://gbl.114sattacker.example/video"))
        assertFalse(JianPianLink.matches("ftp://gbl.114s.example@evil.example/video"))
    }

    @Test
    fun preservesLiteralPlusAndEncodedFilenamePipes() {
        assertEquals(
            "ftp://gbl.114s.example/a+b.mp4",
            JianPianLink.nativePath("tvbox-xg:xgplay://gbl.114s.example/a+b.mp4"),
        )
        assertEquals(
            "ftp://gbl.114s.example/a|b.mp4",
            JianPianLink.nativePath("ftp://gbl.114s.example/a%7Cb.mp4"),
        )
        assertEquals(
            "ftp://gbl.114s.example/a|b.mp4",
            JianPianLink.nativePath("ftp://gbl.114s.example/a%7Cb.mp4|User-Agent=TV"),
        )
    }

    @Test
    fun stripsDecodedPipeMetadataAndNormalizesTheNativePath() {
        assertEquals(
            "ftp://gbl.114s.example/中文.mp4",
            JianPianLink.nativePath("tvbox-xg:xgplay://gbl.114s.example/%E4%B8%AD%E6%96%87.mp4%7CUser-Agent=TV"),
        )
        assertEquals(
            "ftp://gbl.114s.example/video",
            JianPianLink.nativePath("jianpian://pathtype=url&path=xg://gbl.114s.example/video|token"),
        )
        assertEquals(
            "ftp://GBL.114S.example/Film.MP4",
            JianPianLink.nativePath("FTP://GBL.114S.example/Film.MP4"),
        )
    }
}
