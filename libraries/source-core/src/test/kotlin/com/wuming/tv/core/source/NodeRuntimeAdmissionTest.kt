package com.wuming.tv.core.source

import org.junit.Assert.*
import org.junit.Test

class NodeRuntimeAdmissionTest {
    @Test fun distinguishesNodeFromOrdinaryJavaScriptAndOtherSourceTypes() {
        for (api in listOf("node:https://example.test/a.js", "nodejs:https://example.test/a.js", "https://example.test/a.cjs?q=1", "https://example.test/a.MJS#x")) {
            assertEquals(api, TvBoxSourceRuntime.NODE_RUNTIME_REQUIRED, TvBoxSourceRuntimePolicy.classify(3, api))
        }
        assertEquals(TvBoxSourceRuntime.DIRECT_JAVASCRIPT, TvBoxSourceRuntimePolicy.classify(3, "https://example.test/a.js#x"))
        assertFalse(TvBoxSourceRuntimePolicy.requiresNode("https://example.test/a.js?next=a.mjs"))
        assertEquals(TvBoxSourceRuntime.MAC_CMS_API, TvBoxSourceRuntimePolicy.classify(1, "https://example.test/a.mjs"))
    }
}
