package com.wuming.tv.desktop.cloud

import com.wuming.tv.core.cloud.CredentialBackupCodec
import com.wuming.tv.core.runtime.cloudlogin.CloudCredentialStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class DesktopCredentialBackupTest {
    private class MemoryStore : CloudCredentialStore {
        val values = LinkedHashMap<String, String>()
        override fun read(providerId: String) = values[providerId].orEmpty()
        override fun write(providerId: String, credential: String) { values[providerId] = credential }
        override fun remove(providerId: String) { values.remove(providerId) }
    }

    @Test
    fun androidVaultAndCookieBackupRestoresDesktopAccountKeys() {
        val now = 1_800_000_000_000L
        val values = mapOf(
            "vault:opendrive/alipan/token" to "ali-token-json",
            "cookies" to """{
              "quark.cn|__pus":"__pus=quark-session;domain=drive.quark.cn;path=/;expiresAt=${now + 1000};secure=true",
              "uc.cn|__pus":"__pus=uc-session;domain=drive.uc.cn;path=/;expiresAt=${now + 1000};secure=true",
              "baidu.com|BDUSS":"BDUSS=baidu-session;domain=baidu.com;path=/;expiresAt=${now + 1000};secure=true",
              "bilibili.com|SESSDATA":"SESSDATA=bili-session;domain=bilibili.com;path=/;expiresAt=${now + 1000};secure=true",
              "quark.cn|expired":"expired=old;domain=quark.cn;path=/;expiresAt=${now - 1}",
              "quark.cn|foreign":"foreign=leak;domain=other.example;path=/;expiresAt=${now + 1000}"
            }""",
            "connectors" to "[]",
        )

        val mapped = DesktopCredentialBackup.desktopAccounts(values, now)

        assertEquals("ali-token-json", mapped["alipan"])
        assertEquals("__pus=quark-session", mapped["quark"])
        assertEquals("__pus=uc-session", mapped["uc"])
        assertEquals("BDUSS=baidu-session", mapped["baidu"])
        assertEquals("SESSDATA=bili-session", mapped["bilibili"])
        assertEquals(5, mapped.size)
    }

    @Test
    fun importRejectsBackupWithNoDesktopAccounts() {
        val file = Files.createTempFile("desktop-credentials", ".tvbox-credentials.json")
        val passphrase = "example-passphrase".toCharArray()
        try {
            Files.write(file, CredentialBackupCodec.encrypt(mapOf("connectors" to "[]"), passphrase))
            assertThrows(IllegalArgumentException::class.java) {
                DesktopCredentialBackup.importFrom(MemoryStore(), file, passphrase)
            }
        } finally {
            Files.deleteIfExists(file)
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun encryptedAndroidBackupImportsARecognizedAccount() {
        val file = Files.createTempFile("desktop-android-credentials", ".tvbox-credentials.json")
        val passphrase = "example-passphrase".toCharArray()
        val store = MemoryStore()
        try {
            Files.write(file, CredentialBackupCodec.encrypt(
                mapOf("vault:opendrive/alipan/token" to "ali-token-json", "connectors" to "[]"),
                passphrase,
            ))
            assertEquals(1, DesktopCredentialBackup.importFrom(store, file, passphrase))
            assertEquals("ali-token-json", store.read("alipan"))
            assertEquals("", store.read("connectors"))
        } finally {
            Files.deleteIfExists(file)
            passphrase.fill('\u0000')
        }
    }

    @Test
    fun earlierVerbatimImportIsMigratedWithoutReplacingDesktopLogin() {
        val store = MemoryStore()
        store.values["vault:opendrive/alipan/token"] = "android-token"
        store.values["alipan"] = "existing-desktop-token"
        store.values["cookies"] = """{"uc.cn|__pus":"__pus=uc-session;domain=uc.cn;expiresAt=4102444800000"}"""

        assertEquals(1, DesktopCredentialBackup.migratePreviouslyImportedAndroidKeys(store))
        assertEquals("existing-desktop-token", store.read("alipan"))
        assertEquals("__pus=uc-session", store.read("uc"))
        assertEquals(0, DesktopCredentialBackup.migratePreviouslyImportedAndroidKeys(store))
    }
}
