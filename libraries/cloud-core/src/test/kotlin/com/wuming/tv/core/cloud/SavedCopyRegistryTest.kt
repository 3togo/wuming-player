package com.wuming.tv.core.cloud

import org.junit.Assert.*
import org.junit.Test

class SavedCopyRegistryTest {
    private class Store : SavedCopyStore {
        var records = emptyList<SavedCopyRecord>()
        override fun <T> locked(action: () -> T): T = synchronized(this) { action() }
        override fun read() = records
        override fun write(records: List<SavedCopyRecord>) { this.records = records.toList() }
    }

    @Test fun reusesCopiesAndPreservesAllLiveOwners() {
        val store = Store()
        var saves = 0
        fun registry(owner: Long) = SavedCopyRegistry(store, owner, { true }, { 100 })
        fun resolve(owner: Long) = registry(owner).obtain("account", "share", "folder", { true }, { error("delete") }) {
            saves++; "file"
        }
        assertEquals("file", resolve(1))
        assertEquals("file", resolve(2))
        assertEquals(1, saves)
        assertEquals(listOf(1L, 2L), store.records.single().owners)
        registry(2).obtain("other-account", "share", "folder", { error("cross account") }, { error("delete") }) { "other" }
        assertEquals(2, store.records.size)
    }

    @Test fun onlyOldInactiveSameAccountAndFolderCopiesAreReclaimed() {
        val store = Store()
        store.records = (0..9).map { SavedCopyRecord("account", "$it", "folder", "$it", 0, listOf(1)) } +
            listOf(SavedCopyRecord("other", "other", "folder", "other", 0, listOf(1)),
                SavedCopyRecord("account", "different", "different", "different", 0, listOf(1)),
                SavedCopyRecord("account", "live", "folder", "live", 0, listOf(2)),
                SavedCopyRecord("account", "recent", "folder", "recent", 999, listOf(1)))
        val deleted = mutableListOf<String>()
        SavedCopyRegistry(store, 3, { it == 2L }, { 1000 }, 100).obtain("account", "new", "folder", { true }, {
            deleted += it.file; true
        }) { "new" }
        assertEquals((0..7).map(Int::toString), deleted)
        assertTrue(store.records.map { it.file }.containsAll(listOf("other", "different", "live", "recent", "new")))
    }

    @Test fun capacityAndDeletionFailureNeverPermitUnboundedNewSaves() {
        val store = Store()
        store.records = (0..9).map { SavedCopyRecord("account", "$it", "folder", "$it", 0, listOf(1)) }
        assertThrows(java.io.IOException::class.java) {
            SavedCopyRegistry(store, 2, { false }, { 1000 }, 100, 10)
                .obtain("account", "new", "folder", { true }, { false }) { error("must not save") }
        }
        assertEquals(10, store.records.size)
    }

    @Test fun replayedDestinationCannotBeDeletedWhileAnotherRecordProtectsIt() {
        val store = Store()
        store.records = (0..9).map { SavedCopyRecord("account", "$it", "folder", "same-file", 0, listOf(1)) } +
            SavedCopyRecord("account", "live", "folder", "same-file", 0, listOf(2))
        SavedCopyRegistry(store, 3, { it == 2L }, { 1000 }, 100)
            .obtain("account", "new", "folder", { true }, { error("protected destination") }) { "new-file" }
        assertEquals(12, store.records.size)
    }

    @Test fun unknownSubmissionRequiresExplicitLocalAbandonmentBeforeRetry() {
        val store = Store()
        val registry = SavedCopyRegistry(store, 1, { false })
        fun attempt(start: () -> String) = registry.obtainTask("account", "share", "folder", { false }, { false }, start) { "file" }
        assertThrows(java.io.IOException::class.java) { attempt { throw java.io.IOException("lost response") } }
        assertEquals(SavedCopyReview.State.UNCERTAIN, registry.review("account").single().state)
        assertThrows(java.io.IOException::class.java) { attempt { error("must not resubmit") } }
        val record = store.records.single()
        assertEquals(1, registry.forget("account", listOf(record)).changed)
        assertEquals("file", attempt { "new-task" })
    }

    @Test fun staleCleanupApprovalCannotDeleteNewlyPinnedCopyAndOtherSessionsAreLocalOnly() {
        val store = Store()
        val old = SavedCopyRecord("account", "share", "folder", "file", 0, listOf(1))
        val other = old.copy(account = "old-session", file = "other")
        store.records = listOf(old, other)
        val registry = SavedCopyRegistry(store, 2, { it == 2L }, { 1000 }, 100)
        assertEquals(SavedCopyReview.State.ELIGIBLE, registry.review("account").first().state)
        registry.obtain("account", "share", "folder", { true }, { error("delete") }) { error("save") }
        assertEquals(0, registry.clean("account", listOf(old, other), { error("stale or foreign") }, { error("delete") }).changed)
        assertEquals(1, registry.forget("account", listOf(other)).changed)
        assertEquals("file", store.records.single().file)
    }

    @Test fun explicitMaintenanceReconcilesEveryKnownPendingTaskInRecordedFolders() {
        val store = Store()
        store.records = listOf(SavedCopyRecord("account", "share", "folder", "", 0, listOf(1), "task"))
        val registry = SavedCopyRegistry(store, 2, { false })
        assertEquals(1, registry.reconcile("account") { assertEquals("task", it.task); "file" }.changed)
        assertEquals("file", store.records.single().file)
        assertEquals(SavedCopyReview.State.PROTECTED, registry.review("account").single().state)
    }

    @Test fun terminalSaveStateTakesPrecedenceOverAnEarlyFileId() {
        assertEquals(QuarkSavePolicy.State.PENDING, QuarkSavePolicy.state(1, "file", 0, 0))
        assertEquals(QuarkSavePolicy.State.COMPLETE, QuarkSavePolicy.state(2, "file", 0, 0))
        assertEquals(QuarkSavePolicy.State.FAILED, QuarkSavePolicy.state(3, "file", 0, 0))
        assertEquals(QuarkSavePolicy.State.QUOTA_EXHAUSTED, QuarkSavePolicy.state(3, "file", 0, 1))
        assertEquals(QuarkSavePolicy.State.MISSING_FILE, QuarkSavePolicy.state(2, "", 0, 0))
    }
}
