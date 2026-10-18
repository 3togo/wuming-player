package com.wuming.tv.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthPolicyTest {

    @Test
    fun providerIsTemporarilyBlockedAfterRepeatedFailures() {
        val health = ProviderHealthPolicy()
        val now = 1_000L

        health.failure("site", now)
        health.failure("site", now)
        assertEquals(0, health.retryAfter("site", now))

        health.failure("site", now)
        assertTrue(health.retryAfter("site", now) > 0)
        assertEquals(0, health.retryAfter("site", now + ProviderHealthPolicy.COOLDOWN_MS))
    }

    @Test
    fun successfulCallClearsFailureHistory() {
        val health = ProviderHealthPolicy()
        val now = 1_000L

        health.failure("site", now)
        health.failure("site", now)
        health.success("site")
        health.failure("site", now)

        assertEquals(0, health.retryAfter("site", now))
    }

    @Test
    fun isolatedFailuresExpireWithoutAHealthQuery() {
        val health = ProviderHealthPolicy()
        health.failure("site", 0)
        health.failure("site", 1)
        val later = 1 + ProviderHealthPolicy.FAILURE_WINDOW_MS
        health.failure("site", later)
        assertEquals(0, health.retryAfter("site", later))
        health.failure("site", later + 1)
        health.failure("site", later + 2)
        assertEquals(ProviderHealthPolicy.COOLDOWN_MS, health.retryAfter("site", later + 2))
    }

    @Test
    fun failuresJustInsideWindowStillOpenCircuit() {
        val health = ProviderHealthPolicy()
        health.failure("site", 0)
        health.failure("site", 1)
        val later = ProviderHealthPolicy.FAILURE_WINDOW_MS
        health.failure("site", later)
        assertEquals(ProviderHealthPolicy.COOLDOWN_MS, health.retryAfter("site", later))
    }

    @Test
    fun inFlightFailuresDoNotExtendCooldownOrPoisonRecovery() {
        val health = ProviderHealthPolicy()
        for (i in 0 until 3) health.failure("site", 0)
        for (i in 0 until 5) health.failure("site", 1)
        assertEquals(ProviderHealthPolicy.COOLDOWN_MS - 1, health.retryAfter("site", 1))
        val recovered = ProviderHealthPolicy.COOLDOWN_MS
        health.failure("site", recovered)
        health.failure("site", recovered + 1)
        assertEquals(0, health.retryAfter("site", recovered + 1))
        health.failure("site", recovered + 2)
        assertEquals(ProviderHealthPolicy.COOLDOWN_MS, health.retryAfter("site", recovered + 2))
    }

    @Test
    fun clockRollbackStartsNewFailureHistory() {
        val health = ProviderHealthPolicy()
        health.failure("site", 100)
        health.failure("site", 101)
        health.failure("site", 50)
        assertEquals(0, health.retryAfter("site", 50))
        health.failure("site", 51)
        health.failure("site", 52)
        assertEquals(0, health.retryAfter("site", 40))
    }

    @Test
    fun providersRemainIndependentAndClearResetsAll() {
        val health = ProviderHealthPolicy()
        for (i in 0 until 3) health.failure("one", 0)
        health.failure("two", 0)
        assertEquals(0, health.retryAfter("two", 0))
        assertEquals(ProviderHealthPolicy.COOLDOWN_MS, health.retryAfter("one", 0))
        health.clear()
        assertEquals(0, health.retryAfter("one", 0))
    }
}
