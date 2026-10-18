package com.wuming.tv.desktop.cloud

import com.wuming.tv.core.cloud.SavedCopyRecord
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.IOException

class DesktopSavedCopyStoreTest {
    @Test fun pendingTaskSurvivesStoreRecreation() {
        val directory = Files.createTempDirectory("pending-copy-test")
        try {
            val path = directory.resolve("ledger.json")
            val record = SavedCopyRecord("a".repeat(64), "b".repeat(64), "folder", "", 100, listOf(1), "task")
            DesktopSavedCopyStore(path).let { store -> store.locked { store.write(listOf(record)) } }
            DesktopSavedCopyStore(path).let { store -> store.locked { assertEquals(listOf(record), store.read()) } }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun persistsOwnershipAcrossInstancesAndRejectsCorruptLedger() {
        val directory = Files.createTempDirectory("saved-copy-store-test")
        try {
            val path = directory.resolve("ledger.json")
            val record = SavedCopyRecord("a".repeat(64), "b".repeat(64), "folder", "file", 100, listOf(1))
            DesktopSavedCopyStore(path).let { store -> store.locked { store.write(listOf(record)) } }
            DesktopSavedCopyStore(path).let { store -> store.locked { assertEquals(listOf(record), store.read()) } }
            Files.writeString(path, "not-json")
            DesktopSavedCopyStore(path).let { store ->
                assertThrows(IOException::class.java) { store.locked { store.read() } }
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
