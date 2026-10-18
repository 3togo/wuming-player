package com.wuming.tv.core.source.config

import org.junit.Assert.*
import org.junit.Test

class BundledStartupPolicyTest {
    @Test fun selectsThePreferredBundledEntryRatherThanFirstEntry() {
        val other = BundledStartupPolicy.Source("Other", "https://other.test/config")
        val preferred = BundledStartupPolicy.Source("Preferred", "https://preferred.test/config")
        assertEquals(preferred, BundledStartupPolicy.select(listOf(other, preferred), "Preferred"))
    }

    @Test fun removedPreferredSourceDoesNotSelectAnUnrelatedEntry() {
        assertNull(BundledStartupPolicy.select(
            listOf(BundledStartupPolicy.Source("Other", "https://other.test/config")), "Preferred"))
    }

    @Test fun emptyOrBlankEntriesHaveNoImplicitNetworkFallback() {
        assertNull(BundledStartupPolicy.select(emptyList(), "Preferred"))
        assertNull(BundledStartupPolicy.select(
            listOf(BundledStartupPolicy.Source("Preferred", "  ")), "Preferred"))
    }
}
