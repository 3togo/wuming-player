package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThunderLinkTest {
    @Test
    fun nativeHelperGetsLowercaseSchemesWithoutChangingThePath() {
        assertTrue(ThunderLink.matches("FTP://Example.test/Film.MP4"))
        assertEquals("ftp://Example.test/Film.MP4", ThunderLink.nativeUrl("FTP://Example.test/Film.MP4"))
        assertEquals("ftp://Example.test/Film.MP4", ThunderLink.nativeUrl("FTP://Example.test/Film.MP4|User-Agent=TV"))
        assertEquals("ed2k://|file|Film.MP4|", ThunderLink.nativeUrl("ED2K://|file|Film.MP4|"))
        assertEquals("magnet:?xt=urn:btih:ABC", ThunderLink.nativeUrl("MAGNET:?xt=urn:btih:ABC"))
    }

    @Test
    fun doesNotClaimMalformedOrEmbeddedLinks() {
        assertFalse(ThunderLink.matches("ftp:/video.mp4"))
        assertFalse(ThunderLink.matches("https://example.test/ftp://video.mp4"))
        assertFalse(ThunderLink.matches("thunder://encoded-link"))
    }

    @Test
    fun parserClaimsOnlyLeadingDownloadLinksAndTorrentFiles() {
        assertTrue(ThunderLink.parseCandidate("MAGNET:?xt=urn:btih:ABC"))
        assertTrue(ThunderLink.parseCandidate("THUNDER://encoded-link"))
        assertTrue(ThunderLink.parseCandidate("ED2K://|file|video|"))
        assertTrue(ThunderLink.parseCandidate("https://example.test/video.TORRENT?token=1"))
        assertFalse(ThunderLink.parseCandidate("https://example.test/watch?next=magnet:ABC"))
        assertFalse(ThunderLink.parseCandidate("https://example.test/video.mp4"))
        assertFalse(ThunderLink.parseCandidate("magnet://internal/video?name=video&index=1"))
        assertEquals("magnet:?xt=urn:btih:ABC", ThunderLink.parserUrl("MAGNET:?xt=urn:btih:ABC"))
        assertEquals("thunder://encoded-link", ThunderLink.parserUrl("THUNDER://encoded-link"))
    }
}
