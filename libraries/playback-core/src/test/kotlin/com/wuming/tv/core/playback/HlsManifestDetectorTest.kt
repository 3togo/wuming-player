package com.wuming.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsManifestDetectorTest {

    @Test
    fun isManifestDetectsPlainM3u8() {
        assertTrue(HlsManifestDetector.isManifest("#EXTM3U\n#EXT-X-VERSION:3".toByteArray()))
        assertTrue(HlsManifestDetector.isManifest("#EXTM3U".toByteArray()))
    }

    @Test
    fun isManifestHandlesBomAndWhitespace() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val content = "#EXTM3U".toByteArray()
        assertTrue(HlsManifestDetector.isManifest(bom + content))
        assertTrue(HlsManifestDetector.isManifest("  \r\n#EXTM3U".toByteArray()))
    }

    @Test
    fun isManifestRejectsNonManifest() {
        assertFalse(HlsManifestDetector.isManifest("not a manifest".toByteArray()))
        assertFalse(HlsManifestDetector.isManifest(byteArrayOf()))
        assertFalse(HlsManifestDetector.isManifest(byteArrayOf(0x47, 0x40, 0x00)))
    }

    @Test
    fun transportStreamOffsetFindsSyncByte() {
        val data = ByteArray(188 * 5)
        data[0] = 0x47
        data[188] = 0x47
        data[188 * 2] = 0x47
        data[188 * 3] = 0x47
        assertEquals(0, HlsManifestDetector.transportStreamOffset(data))
    }

    @Test
    fun transportStreamOffsetFindsOffsetSyncByte() {
        val data = ByteArray(200 + 188 * 4)
        data[200] = 0x47
        data[200 + 188] = 0x47
        data[200 + 188 * 2] = 0x47
        data[200 + 188 * 3] = 0x47
        assertEquals(200, HlsManifestDetector.transportStreamOffset(data))
    }

    @Test
    fun transportStreamOffsetReturnsMinusOneForNoSync() {
        val data = ByteArray(1024)
        assertEquals(-1, HlsManifestDetector.transportStreamOffset(data))
    }

    @Test
    fun looksLikeErrorDocumentDetectsHtml() {
        assertTrue(HlsManifestDetector.looksLikeErrorDocument("<!doctype html><html>".toByteArray()))
        assertTrue(HlsManifestDetector.looksLikeErrorDocument("<html><head>".toByteArray()))
        assertTrue(HlsManifestDetector.looksLikeErrorDocument("<body>error</body>".toByteArray()))
    }

    @Test
    fun looksLikeErrorDocumentDetectsJsonAndForbidden() {
        assertTrue(HlsManifestDetector.looksLikeErrorDocument("{\"error\": \"403 forbidden\"}".toByteArray()))
        assertTrue(HlsManifestDetector.looksLikeErrorDocument("access denied".toByteArray()))
    }

    @Test
    fun looksLikeErrorDocumentRejectsBinaryAndManifest() {
        assertFalse(HlsManifestDetector.looksLikeErrorDocument("#EXTM3U".toByteArray()))
        assertFalse(HlsManifestDetector.looksLikeErrorDocument(byteArrayOf(0x47, 0x40)))
    }

    @Test
    fun segmentCountCountsExtinfLines() {
        assertEquals(3, HlsManifestDetector.segmentCount(
            "#EXTM3U\n#EXTINF:10\nseg1\n#EXTINF:10\nseg2\n#EXTINF:10\nseg3\n#EXT-X-ENDLIST"))
        assertEquals(0, HlsManifestDetector.segmentCount("#EXTM3U\n#EXT-X-ENDLIST"))
    }
}
