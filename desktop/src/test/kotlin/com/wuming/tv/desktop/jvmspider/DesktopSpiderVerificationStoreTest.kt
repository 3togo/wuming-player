package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.catalog.CatalogCapability
import com.wuming.tv.core.catalog.CatalogVerification
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.*
import org.junit.Test

class DesktopSpiderVerificationStoreTest {
    private val site = JvmSpiderSiteSpec("new", "New", "csp_NotInTheList", "secret", "https://example.test/spider.jar")
    private val trust = GuardedSourceTrust("a".repeat(64), GuardedSpiderAbi.ARMEABI_V7A)

    @Test fun persistsMeasuredUnknownProviderAndExpiresEachCapability() {
        val prefs = Preferences.userRoot().node("spider-evidence-test/${UUID.randomUUID()}")
        try {
            var now = 100L
            val store = DesktopSpiderVerificationStore(prefs) { now }
            val key = DesktopSpiderVerificationStore.identity(site, trust)
            assertEquals(GuardedDesktopSupport.UNVERIFIED, GuardedDesktopSupportPolicy.classify(site))
            assertNull(store.support(key, false))
            store.record(key, setOf(CatalogCapability.LISTING, CatalogCapability.DETAIL))
            assertEquals(GuardedDesktopSupport.VERIFIED_CATALOG, DesktopSpiderVerificationStore(prefs) { now }.support(key, false))
            store.record(key, setOf(CatalogCapability.PLAYBACK_URL))
            assertEquals(GuardedDesktopSupport.VERIFIED_PLAYBACK_URL, store.support(key, false))
            now += CatalogVerification.MAX_AGE_MILLIS + 1
            store.record(key, setOf(CatalogCapability.LISTING))
            assertNull(store.support(key, false))
            assertFalse(prefs.get(key, "").contains("secret"))
            prefs.put(key, "BROKEN:not-a-time,DETAIL:garbage")
            assertNull(store.support(key, false))
        } finally { prefs.removeNode(); prefs.flush() }
    }

    @Test fun changedInputsCannotReuseEvidenceButCosmeticNamesCan() {
        val identity = DesktopSpiderVerificationStore.identity(site, trust)
        assertEquals(identity, DesktopSpiderVerificationStore.identity(site.copy(name = "Renamed"), trust))
        for (other in listOf(site.copy(key = "other"), site.copy(api = "csp_Other"), site.copy(extension = "new token"),
            site.copy(artifactLocation = "https://other.test/spider.jar"), site.copy(categories = listOf("Movies")))) {
            assertNotEquals(identity, DesktopSpiderVerificationStore.identity(other, trust))
        }
        assertNotEquals(identity, DesktopSpiderVerificationStore.identity(site, trust.copy(sha256 = "b".repeat(64))))
        assertNotEquals(identity, DesktopSpiderVerificationStore.identity(site, trust.copy(abi = GuardedSpiderAbi.ARM64_V8A)))
        assertNotEquals(identity, DesktopSpiderVerificationStore.identity(site, trust, "next-runtime"))
        assertTrue(identity.length <= Preferences.MAX_KEY_LENGTH)
    }
}
