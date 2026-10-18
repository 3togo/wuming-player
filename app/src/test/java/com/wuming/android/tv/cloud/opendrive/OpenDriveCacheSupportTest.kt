package com.wuming.android.tv.cloud.opendrive

import com.wuming.android.tv.cloud.CloudCacheRecord
import com.wuming.android.tv.cloud.CloudStorageProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the eligibility rules that decide which provider-side copies may be deleted.
 * A wrong answer here deletes a file somebody is still watching.
 */
class OpenDriveCacheSupportTest {

    private fun record(
        provider: CloudStorageProvider = CloudStorageProvider.QUARK,
        folderId: String = "folder-1",
        fileId: String,
        createdAt: Long = 0L,
        state: Int = CloudCacheRecord.ACTIVE,
        accountHash: String = "account-1",
    ) = CloudCacheRecord().also {
        it.provider = provider.id
        it.accountHash = accountHash
        it.remoteFolderId = folderId
        it.remoteFileId = fileId
        it.state = state
        it.createdAt = createdAt
        it.updatedAt = createdAt
    }

    private fun reclaimable(
        records: List<CloudCacheRecord?>?,
        provider: CloudStorageProvider? = CloudStorageProvider.QUARK,
        folderId: String? = "folder-1",
        protectedFileIds: Set<String?>? = null,
    ) = OpenDriveCacheSupport.reclaimable(records, provider, folderId, protectedFileIds)

    @Test
    fun nothingIsReclaimedWithoutCandidates() {
        assertTrue(reclaimable(null).isEmpty())
        assertTrue(reclaimable(emptyList()).isEmpty())
        assertTrue(reclaimable(listOf(record(fileId = "a")), provider = null).isEmpty())
        assertTrue(reclaimable(listOf(record(fileId = "a")), folderId = null).isEmpty())
        assertTrue(reclaimable(listOf(record(fileId = "a")), folderId = "").isEmpty())
        assertTrue(reclaimable(listOf(record(fileId = "a")), folderId = "   ").isEmpty())
    }

    @Test
    fun onlyLiveCopiesInThisAccountFolderAreReclaimed() {
        val candidates = listOf(
            record(fileId = "keep-deleted", state = CloudCacheRecord.DELETED),
            record(fileId = "keep-failed", state = CloudCacheRecord.FAILED),
            record(provider = CloudStorageProvider.ALIPAN, fileId = "other-provider"),
            record(folderId = "folder-2", fileId = "other-folder"),
            record(fileId = "reclaim-1"),
        )
        assertEquals(listOf("reclaim-1"), reclaimable(candidates).map { it.remoteFileId })
    }

    @Test
    fun staleUnknownStatesAreNeverReclaimed() {
        val unknown = record(fileId = "mystery", state = 99)
        assertTrue(reclaimable(listOf(unknown)).isEmpty())
    }

    @Test
    fun nullEntriesAreSkippedInsteadOfCrashingTheSweep() {
        val result = reclaimable(listOf(null, record(fileId = "a"), null))
        assertEquals(listOf("a"), result.map { it.remoteFileId })
    }

    @Test
    fun filesAttachedToALiveSessionAreProtected() {
        val candidates = listOf(record(fileId = "playing"), record(fileId = "idle"))
        val protected = reclaimable(candidates, protectedFileIds = setOf("playing"))
        assertEquals(listOf("idle"), protected.map { it.remoteFileId })
        assertEquals(2, reclaimable(candidates, protectedFileIds = emptySet()).size)
        assertEquals(2, reclaimable(candidates).size)
    }

    @Test
    fun aStagedLeaseProtectsItsCopyUntilItIsRetired() {
        val fileId = "leased-" + System.nanoTime()
        OpenDriveCopyUsage.stage(CloudStorageProvider.QUARK.id, "account-1", fileId)
        val candidates = listOf(record(fileId = fileId), record(fileId = "$fileId-free"))
        try {
            assertTrue(OpenDriveCopyUsage.busy(CloudStorageProvider.QUARK.id, fileId))
            assertEquals(
                listOf("$fileId-free"),
                reclaimable(candidates).map { it.remoteFileId },
            )
        } finally {
            OpenDriveCopyUsage.retire(CloudStorageProvider.QUARK.id, fileId)
        }
        assertFalse(OpenDriveCopyUsage.busy(CloudStorageProvider.QUARK.id, fileId))
        assertEquals(2, reclaimable(candidates).size)
    }

    @Test
    fun olderDisposableCopiesAreReclaimedFirst() {
        val candidates = listOf(
            record(fileId = "new", createdAt = 300L),
            record(fileId = "old", createdAt = 100L),
            record(fileId = "middle", createdAt = 200L),
        )
        assertEquals(listOf("old", "middle", "new"), reclaimable(candidates).map { it.remoteFileId })
    }

    @Test
    fun theSweepDoesNotTouchTheCallerList() {
        val candidates = mutableListOf(
            record(fileId = "b", createdAt = 2L),
            record(fileId = "a", createdAt = 1L),
        )
        val before = candidates.map { it.remoteFileId }
        val result = reclaimable(candidates)
        assertEquals(before, candidates.map { it.remoteFileId })
        assertEquals(listOf("a", "b"), result.map { it.remoteFileId })
    }

    @Test
    fun anUnknownAccountIsNotMarkedDeleted() {
        // These guards must return before any repository is reached; a blank account hash
        // would otherwise widen the delete to every account on the provider.
        val stored = record(fileId = "file-1")
        OpenDriveCacheSupport.deleted(CloudStorageProvider.QUARK, null, "file-1")
        OpenDriveCacheSupport.deleted(CloudStorageProvider.QUARK, "", "file-1")
        OpenDriveCacheSupport.deleted(CloudStorageProvider.QUARK, "   ", "file-1")
        assertEquals(CloudCacheRecord.ACTIVE, stored.state)
        assertEquals(listOf("file-1"), reclaimable(listOf(stored)).map { it.remoteFileId })
    }
}
