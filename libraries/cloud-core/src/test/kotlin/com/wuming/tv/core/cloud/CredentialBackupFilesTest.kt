package com.wuming.tv.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialBackupFilesTest {

    @Test
    fun suggestedFilenameCarriesTimestampAndEnvelopeSuffix() {
        val name = CredentialBackupFiles.suggestFilename(0L)
        assertTrue(name.startsWith("無名-credentials-"))
        assertTrue(name.endsWith(CredentialBackupFiles.FILE_SUFFIX))
        assertTrue(name.matches(Regex("無名-credentials-[0-9]{8}-[0-9]{6}\\.tvbox-credentials\\.json")))
        assertFalse(CredentialBackupFiles.suggestFilename(0L) == CredentialBackupFiles.suggestFilename(1_000L))
    }

    @Test
    fun onlyEnvelopeFilesCountAsCredentialBackups() {
        assertTrue(CredentialBackupFiles.isBackupFileName("無名-credentials-20261007-101112.tvbox-credentials.json"))
        assertTrue(CredentialBackupFiles.isBackupFileName("tvbox-accounts-20261007-101112.tvbox-credentials.json"))
        assertFalse(CredentialBackupFiles.isBackupFileName("config.json"))
        assertFalse(CredentialBackupFiles.isBackupFileName(null))
    }

    @Test
    fun truncatedAndUnreadableCopiesAreTooSmall() {
        assertTrue(CredentialBackupFiles.isTooSmall(0L))
        assertTrue(CredentialBackupFiles.isTooSmall(CredentialBackupFiles.MIN_FILE_BYTES - 1))
        assertFalse(CredentialBackupFiles.isTooSmall(CredentialBackupFiles.MIN_FILE_BYTES))
        assertFalse(CredentialBackupFiles.isTooSmall(1024L))
    }

    @Test
    fun legacyCopiesMoveOnlyWhileTheirNameIsFree() {
        val size = CredentialBackupFiles.MIN_FILE_BYTES
        assertEquals(LegacyCredentialBackupAction.MOVE, CredentialBackupFiles.relocation(size, false))
        assertEquals(LegacyCredentialBackupAction.DISCARD, CredentialBackupFiles.relocation(size, true))
        assertEquals(LegacyCredentialBackupAction.DISCARD, CredentialBackupFiles.relocation(0L, false))
    }
}
