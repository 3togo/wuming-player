package com.wuming.tv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogAccessFailureClassifierTest {

    @Test
    fun identifiesSafeLineDenialWithoutProviderSpecificHandling() {
        val failure = CatalogAccessFailureClassifier.classify(
            """
            <!doctype html><html><body>
            <h1>Access Forbidden</h1><p>Blocked For Attack Detected</p>
            <footer>SafeLine WAF</footer><script src="/.safeline/block.js"></script>
            </body></html>
            """.trimIndent(),
        )

        assertEquals(CatalogAccessFailureKind.WAF_ACCESS_DENIED, failure?.kind)
    }

    @Test
    fun identifiesInteractiveBrowserChallenge() {
        val failure = CatalogAccessFailureClassifier.classify(
            "<html><head><title>Just a moment</title></head>" +
                "<body>Verifying your browser before challenge</body></html>",
        )

        assertEquals(CatalogAccessFailureKind.BROWSER_CHALLENGE, failure?.kind)
    }

    @Test
    fun leavesNormalJsonAndUnrelatedHtmlUnclassified() {
        assertNull(CatalogAccessFailureClassifier.classify("{\"list\":[]}"))
        assertNull(CatalogAccessFailureClassifier.classify("<html><body>Catalog maintenance</body></html>"))
    }

    @Test
    fun codecReportsAccessDenialInsteadOfInvalidJson() {
        val codec = CatVodCatalogCodec(CatalogSource("demo", "Demo"))

        val error = assertThrows(CatalogAccessException::class.java) {
            codec.search("<html><body>Access Forbidden by SafeLine WAF</body></html>")
        }

        assertEquals(CatalogAccessFailureKind.WAF_ACCESS_DENIED, error.failure.kind)
    }
}
