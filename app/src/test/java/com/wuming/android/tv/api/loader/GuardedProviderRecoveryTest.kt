package com.wuming.android.tv.api.loader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuardedProviderRecoveryTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun rejectsUnrecognizedProviderAndPayloadWithoutChangingThem() {
        val provider = directory.newFile("provider.jar").apply { writeText("unknown provider") }
        val payload = directory.newFile("config.db").apply { writeText("untrusted payload") }
        assertFalse(GuardedProviderRecovery.matches(provider, payload))
        assertEquals("unknown provider", provider.readText())
        assertEquals("untrusted payload", payload.readText())
    }

    @Test fun missingExtractionDoesNotTriggerRecovery() {
        val provider = directory.newFile("provider.jar")
        assertFalse(GuardedProviderRecovery.matches(provider, java.io.File(directory.root, "missing")))
    }
}
