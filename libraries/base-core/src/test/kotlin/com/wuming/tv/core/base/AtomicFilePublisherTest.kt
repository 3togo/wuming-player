package com.wuming.tv.core.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.deleteIfExists

class AtomicFilePublisherTest {

    @Test
    fun publishLeavesNoTemporaryFileBehind() {
        val directory = Files.createTempDirectory("atomic-publish")
        try {
            val target = directory.resolve("state.json")
            AtomicFilePublisher.publish(target, "state-") { Files.writeString(it, "content") }
            assertEquals("content", Files.readString(target))
            assertEquals(0, Files.list(directory).use { it.count() } - 1)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aFailedWriteKeepsThePreviousCopyAndRemovesTheTemporaryFile() {
        val directory = Files.createTempDirectory("atomic-publish")
        try {
            val target = directory.resolve("state.json")
            Files.writeString(target, "previous")
            runCatching {
                AtomicFilePublisher.publish(target, "state-") { Files.writeString(it, "partial"); error("boom") }
            }
            assertEquals("previous", Files.readString(target))
            assertFalse(Files.list(directory).use { it.anyMatch { file -> file.fileName.toString().endsWith(".tmp") } })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun publishCreatesMissingParentDirectories() {
        val directory = Files.createTempDirectory("atomic-publish")
        try {
            val target = directory.resolve("nested/deeper/state.json")
            AtomicFilePublisher.publish(target) { Files.writeString(it, "content") }
            assertEquals("content", Files.readString(target))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownerOnlyRemovesGroupAndOtherAccess() {
        val directory = Files.createTempDirectory("atomic-publish")
        try {
            val file = directory.resolve("secret")
            Files.writeString(file, "credential")
            AtomicFilePublisher.ownerOnly(file)
            val permissions = Files.getPosixFilePermissions(file)
            assertTrue(permissions.contains(PosixFilePermission.OWNER_READ))
            assertFalse(permissions.contains(PosixFilePermission.GROUP_READ))
            assertFalse(permissions.contains(PosixFilePermission.OTHERS_READ))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun moveReplacesAnExistingTarget() {
        val directory = Files.createTempDirectory("atomic-publish")
        try {
            val target = directory.resolve("state.json")
            Files.writeString(target, "previous")
            val temporary = Files.createTempFile(directory, "state-", ".tmp")
            Files.writeString(temporary, "next")
            AtomicFilePublisher.move(temporary, target)
            assertEquals("next", Files.readString(target))
            assertFalse(Files.exists(temporary))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun java.nio.file.Path.deleteRecursively() {
        Files.walk(this).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
    }
}
