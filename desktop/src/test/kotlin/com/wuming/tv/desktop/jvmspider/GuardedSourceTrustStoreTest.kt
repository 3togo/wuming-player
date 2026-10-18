package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import java.util.UUID
import java.util.prefs.Preferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GuardedSourceTrustStoreTest {
    private lateinit var preferences: Preferences

    @Before
    fun createNode() {
        preferences = Preferences.userRoot().node("tvbox-guarded-trust-test/${UUID.randomUUID()}")
    }

    @After
    fun removeNode() {
        preferences.removeNode()
        preferences.flush()
    }

    @Test
    fun `approval is scoped to provider identity digest and ABI`() {
        val store = GuardedSourceTrustStore(preferences)
        val site = JvmSpiderSiteSpec("cz", "Cz", "csp_NewCzGuard", "", "https://example.test/spider.jar")
        val changedIdentity = site.copy(api = "csp_LibvioGuard")
        val sha256 = "a".repeat(64)

        assertNull(store.find(site))
        store.remember(site, sha256, GuardedSpiderAbi.ARMEABI_V7A)

        assertEquals(sha256, store.find(site)?.sha256)
        assertEquals(GuardedSpiderAbi.ARMEABI_V7A, store.find(site)?.abi)
        assertNull(store.find(changedIdentity))
    }
}
