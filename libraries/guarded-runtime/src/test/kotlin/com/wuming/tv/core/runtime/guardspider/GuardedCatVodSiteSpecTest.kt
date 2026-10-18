package com.wuming.tv.core.runtime.guardspider

import org.junit.Assert.assertEquals
import org.junit.Test

class GuardedCatVodSiteSpecTest {
    @Test
    fun mapsGuardConfigurationNameToPreparedProviderClass() {
        val spec = GuardedCatVodSiteSpec("cz", "NewCz", "csp_NewCzGuard")
        assertEquals("NewCz", spec.providerSimpleName)
        assertEquals("com.github.catvod.spider.NewCz", spec.className)
    }
}
