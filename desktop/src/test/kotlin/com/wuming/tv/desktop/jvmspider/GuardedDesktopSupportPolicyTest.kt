package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class GuardedDesktopSupportPolicyTest {

    @Test
    fun allowsOnlyProvidersMeasuredOnTheDesktopRuntime() {
        assertEquals(GuardedDesktopSupport.VERIFIED, GuardedDesktopSupportPolicy.classify(site("NewCz")))
        assertEquals(GuardedDesktopSupport.VERIFIED, GuardedDesktopSupportPolicy.classify(site("Music")))
        assertEquals(GuardedDesktopSupport.VERIFIED_INDEX, GuardedDesktopSupportPolicy.classify(site("DouDou")))
        assertEquals(GuardedDesktopSupport.CLOUD_SEARCH, GuardedDesktopSupportPolicy.classify(site("Seedhub")))
        assertEquals(GuardedDesktopSupport.UNVERIFIED, GuardedDesktopSupportPolicy.classify(site("Unknown")))
    }

    @Test
    fun enablesMeasuredCataloguesWithoutClaimingVerifiedPlayback() {
        for (provider in listOf("Nmyswv", "Bili")) {
            for (api in listOf("csp_${provider}Guard", "csp_$provider")) {
                val support = GuardedDesktopSupportPolicy.classify(site(provider).copy(api = api))
                assertEquals(GuardedDesktopSupport.VERIFIED_CATALOG, support)
                assertTrue(support.activationAllowed)
                assertTrue(support.label.contains("playback unverified"))
            }
        }
        for (provider in listOf("Hmys", "AppSx", "YGP", "LiveGz", "Push", "XPath")) {
            val support = GuardedDesktopSupportPolicy.classify(site(provider))
            assertEquals(GuardedDesktopSupport.UNVERIFIED, support)
            assertFalse(support.activationAllowed)
        }
    }

    private fun site(provider: String) = JvmSpiderSiteSpec(
        key = provider,
        name = provider,
        api = "csp_${provider}Guard",
        artifactLocation = "https://example.invalid/spider.jar",
    )
}
