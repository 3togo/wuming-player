package com.wuming.tv.core.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class SavedCopyLeasesTest {
    private val now = AtomicLong()
    private val leases = SavedCopyLeases({ now.get() }, 100)
    private val key = SavedCopyLeases.Key("uc", "account", "copy")

    @Test fun abandonedResultsExpireAndDifferentProviderIsIndependent() {
        leases.stage(key)
        assertTrue(leases.busy("uc", "copy"))
        assertFalse(leases.busy("quark", "copy"))
        now.set(101)
        assertTrue(leases.delete(key) { true })
    }

    @Test fun ownershipRequiresAccountProviderMarkerAndActiveRecord() {
        assertTrue(SavedCopyPolicy.owned("alipan", "account", "owner:", "alipan", "account", "owner:source", true))
        assertFalse(SavedCopyPolicy.owned("alipan", "other", "owner:", "alipan", "account", "owner:source", true))
        assertFalse(SavedCopyPolicy.owned("uc", "account", "owner:", "alipan", "account", "owner:source", true))
        assertFalse(SavedCopyPolicy.owned("alipan", "account", "owner:", "alipan", "account", "user-file", true))
        assertFalse(SavedCopyPolicy.expired(200, 100, 10))
    }
}
