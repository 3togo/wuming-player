package com.wuming.tv.desktop.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class SecretServiceCredentialStoreTest {

    @Test
    fun missingSecretServiceCannotPretendThatLoginWasPersisted() {
        val store = SecretServiceCredentialStore(null)

        assertFalse(store.persistenceAvailable)
        assertThrows(java.io.IOException::class.java) { store.write("alipan", "credential") }
        assertThrows(java.io.IOException::class.java) { store.remove("alipan") }
    }

    @Test
    fun credentialSurvivesAcrossStoreInstancesInTheUserSecretService() {
        val directory = Files.createTempDirectory("tvbox-secret-service-test")
        val credential = directory.resolve("credential")
        val tool = directory.resolve("secret-tool")
        Files.writeString(tool, """#!/bin/sh
case "${'$'}1" in
  store) IFS= read -r value; printf '%s' "${'$'}value" > '${credential}' ;;
  lookup) test -f '${credential}' && cat '${credential}' ;;
  clear) rm -f '${credential}' ;;
esac
""")
        Files.setPosixFilePermissions(tool, setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        ))

        SecretServiceCredentialStore(tool).write("baidu", "BDUSS=saved-session")
        assertEquals("BDUSS=saved-session", SecretServiceCredentialStore(tool).read("baidu"))
        SecretServiceCredentialStore(tool).remove("baidu")
        assertEquals("", SecretServiceCredentialStore(tool).read("baidu"))
    }
}
