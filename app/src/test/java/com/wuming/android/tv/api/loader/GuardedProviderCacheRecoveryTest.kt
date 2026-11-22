package com.wuming.android.tv.api.loader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class GuardedProviderCacheRecoveryTest {
    @get:Rule val directory = TemporaryFolder()

    private val provider by lazy { directory.newFile("provider.jar").apply { writeText("provider") } }
    private val cache by lazy { directory.newFolder("code_cache") }
    private val payload by lazy {
        File(cache, "sharedb/config.jar").apply { parentFile.mkdirs(); writeText("stale generated code") }
    }
    private val backup get() = File(payload.parentFile, "config.jar.loader-recovery-backup")

    private fun recovery(): GuardedProviderCacheRecovery = GuardedProviderCacheRecovery(
        GuardedProviderRecovery.digest(provider), GuardedProviderRecovery.digest(payload),
    )

    @Test fun quarantinesOnlyThePayloadAndRetriesOnce() {
        val recovery = recovery()
        val other = File(payload.parentFile, "account.json").apply { writeText("keep") }
        assertTrue(recovery.recover(provider, cache) {
            assertFalse(payload.exists())
            assertEquals("stale generated code", backup.readText())
        })
        assertEquals("keep", other.readText())
        payload.writeText("stale generated code")
        assertFalse(recovery.recover(provider, cache) { fail("Second retry") })
    }

    @Test fun failedRetryRetainsBackupAndDoesNotRetryAgain() {
        val recovery = recovery()
        val failure = IllegalStateException("still null")
        try {
            recovery.recover(provider, cache) {
                payload.writeText("new extraction")
                throw failure
            }
            fail("Failure must propagate to loader guard")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        assertEquals("stale generated code", backup.readText())
        assertEquals("new extraction", payload.readText())
        assertFalse(recovery.recover(provider, cache) { fail("Repeated failure") })
    }

    @Test fun rejectsChangedProvider() {
        val recovery = recovery()
        provider.appendText("changed")
        assertFalse(recovery.recover(provider, cache) { fail("Unknown provider") })
        assertTrue(payload.exists())
        assertFalse(backup.exists())
    }

    @Test fun rejectsChangedPayload() {
        val recovery = recovery()
        payload.writeText("different code")
        assertFalse(recovery.recover(provider, cache) { fail("Unknown payload") })
        assertEquals("different code", payload.readText())
    }

    @Test fun missingExtractionDoesNotConsumeRetry() {
        val recovery = recovery()
        payload.delete()
        assertFalse(recovery.recover(provider, cache) { fail("Missing payload") })
        payload.writeText("stale generated code")
        assertTrue(recovery.recover(provider, cache) {})
    }

    @Test fun refusesToOverwriteAnUnrelatedBackup() {
        val recovery = recovery()
        backup.writeText("other evidence")
        assertFalse(recovery.recover(provider, cache) { fail("Backup collision") })
        assertEquals("other evidence", backup.readText())
        assertTrue(payload.exists())
    }

    @Test fun reusesIdenticalBackupAcrossProcessRestarts() {
        val recovery = recovery()
        backup.writeBytes(payload.readBytes())
        assertTrue(recovery.recover(provider, cache) {})
        assertFalse(payload.exists())
        assertEquals("stale generated code", backup.readText())
    }

    @Test fun rejectsSymlinkedPayload() {
        val recovery = recovery()
        val outside = directory.newFile("outside").apply { writeBytes(payload.readBytes()) }
        payload.delete()
        Files.createSymbolicLink(payload.toPath(), outside.toPath())
        assertFalse(recovery.recover(provider, cache) { fail("Redirected payload") })
        assertTrue(outside.exists())
    }

    @Test fun rejectsSymlinkedBackupEvenWhenTargetIsMissing() {
        val recovery = recovery()
        Files.createSymbolicLink(backup.toPath(), File(directory.root, "missing").toPath())
        assertFalse(recovery.recover(provider, cache) { fail("Redirected backup") })
        assertTrue(payload.exists())
    }

    @Test fun rejectsRedirectedExtractionDirectory() {
        val recovery = recovery()
        val outside = directory.newFolder("outside-directory")
        assertTrue(payload.parentFile.renameTo(File(outside, "sharedb")))
        Files.createSymbolicLink(payload.parentFile.toPath(), File(outside, "sharedb").toPath())
        assertFalse(recovery.recover(provider, cache) { fail("Redirected directory") })
        assertEquals("stale generated code", payload.readText())
        assertFalse(backup.exists())
    }

    @Test fun cancellationDoesNotChangeFiles() {
        val recovery = recovery()
        Thread.currentThread().interrupt()
        try {
            assertFalse(recovery.recover(provider, cache) { fail("Cancelled initialization") })
            assertTrue(payload.exists())
            assertFalse(backup.exists())
        } finally {
            Thread.interrupted()
        }
    }
}
