package com.wuming.tv.core.catalog

import org.junit.Assert.*
import org.junit.Test

class CatalogPosterMirrorsTest {
    @Test fun preservesPathAndSignedQueryOnAdvertisedMirrors() {
        val url = "http://img.ypfbj.com/upload/a%20b.jpg?token=a%2Fb"
        val candidates = CatalogPosterMirrors.candidates(url)
        assertEquals(url, candidates.first())
        assertEquals("https://img.cqkgy.com/upload/a%20b.jpg?token=a%2Fb", candidates[1])
        assertEquals(3, candidates.size)
    }
    @Test fun doesNotRewriteUnrelatedOrCredentialedLocations() {
        for (url in listOf("https://mg.lzipic.com/a.jpg", "https://img.ypfbj.com.evil.test/a.jpg", "https://user@img.ypfbj.com/a.jpg", "https://img.ypfbj.com:443/a.jpg"))
            assertEquals(listOf(url), CatalogPosterMirrors.candidates(url))
    }
}
