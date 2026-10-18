package com.wuming.tv.desktop.js

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopJsLocalStorageTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun persistsProviderScopedValuesAtomically() {
        val root = temporary.newFolder("storage").toPath()
        DesktopJsLocalStorage.open(root, "provider-a").apply {
            set("session", "token", "value-a")
            set("cache", "token", "value-b")
        }

        val reopened = DesktopJsLocalStorage.open(root, "provider-a")
        assertEquals("value-a", reopened.get("session", "token"))
        assertEquals("value-b", reopened.get("cache", "token"))
        assertEquals("", DesktopJsLocalStorage.open(root, "provider-b").get("session", "token"))
        assertFalse(root.toFile().walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun enforcesValueQuotaWithoutReplacingExistingState() {
        val root = temporary.newFolder("quota").toPath()
        val storage = DesktopJsLocalStorage.open(root, "provider")
        storage.set("state", "small", "kept")

        assertThrows(IllegalArgumentException::class.java) {
            storage.set("state", "large", "x".repeat(256 * 1024 + 1))
        }
        assertEquals("kept", DesktopJsLocalStorage.open(root, "provider").get("state", "small"))
    }

    @Test
    fun reconciliationClearsDisabledProvidersAcrossRuntimeRestarts() {
        val root = temporary.newFolder("reconcile").toPath()
        DesktopJsLocalStorage.open(root, "active").set("state", "key", "keep")
        DesktopJsLocalStorage.open(root, "removed").set("state", "key", "delete")

        DesktopJsLocalStorage.reconcile(root, setOf("active"))

        assertEquals("keep", DesktopJsLocalStorage.open(root, "active").get("state", "key"))
        assertEquals("", DesktopJsLocalStorage.open(root, "removed").get("state", "key"))
    }
}
