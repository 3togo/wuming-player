package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedSourceActivatorTest {

    private val activator = GuardedSourceActivator()

    @Test
    fun `every csp provider is a candidate without claiming compatibility`() {
        assertTrue(activator.isCandidate(site("csp_NewCzGuard")))
        assertTrue(activator.isCandidate(site("csp_DouDouGuard")))
        assertTrue(activator.isCandidate(site("csp_Demo")))
    }

    private fun site(api: String) = JvmSpiderSiteSpec(
        key = api,
        name = api,
        api = api,
        artifactLocation = "https://example.invalid/spider.jar",
    )
}
