package com.wuming.tv.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

class CredentialBackupCodecTest {

    private val passphrase = "correct horse".toCharArray()

    @Test
    fun roundTripProtectsAndRestoresCredentialValues() {
        val values = LinkedHashMap<String, String>()
        values["vault:opendrive/quark/token"] = "__pus=secret-cookie"
        values["vault:opendrive/webdav/connector_1"] = "{\"password\":\"secret\"}"
        values["cookies"] = "{\"bilibili.com|SESSDATA\":\"session-secret\"}"

        val backup = CredentialBackupCodec.encrypt(values, passphrase)
        val envelope = String(backup, StandardCharsets.UTF_8)

        for (secret in arrayOf("secret-cookie", "password", "session-secret")) {
            assertFalse(envelope.contains(secret))
        }
        assertEquals(values, CredentialBackupCodec.decrypt(backup, passphrase))
    }

    @Test
    fun wrongPassphraseCannotDecrypt() {
        val backup = CredentialBackupCodec.encrypt(mapOf("vault:opendrive/uc/token" to "secret"), passphrase)
        assertThrows(Exception::class.java) {
            CredentialBackupCodec.decrypt(backup, "incorrect horse".toCharArray())
        }
    }

    @Test
    fun tamperingIsRejected() {
        val backup = CredentialBackupCodec.encrypt(mapOf("vault:opendrive/uc/token" to "secret"), passphrase)
        backup[backup.size - 8] = (backup[backup.size - 8].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { CredentialBackupCodec.decrypt(backup, passphrase) }
    }

    @Test
    fun shortPassphrasesAreRejectedForEncryptAndDecrypt() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBackupCodec.encrypt(mapOf("key" to "value"), "short".toCharArray())
        }
        val backup = CredentialBackupCodec.encrypt(mapOf("key" to "value"), passphrase)
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBackupCodec.decrypt(backup, "short".toCharArray())
        }
    }

    @Test
    fun emptyMalformedAndOversizedBackupsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBackupCodec.decrypt(ByteArray(0), passphrase)
        }
        assertThrows(Exception::class.java) {
            CredentialBackupCodec.decrypt("not-json".toByteArray(StandardCharsets.UTF_8), passphrase)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBackupCodec.decrypt(ByteArray(CredentialBackupCodec.MAX_FILE_BYTES + 1), passphrase)
        }
    }

    @Test
    fun unsupportedEnvelopeVersionIsRejected() {
        val backup = CredentialBackupCodec.encrypt(mapOf("key" to "value"), passphrase)
        val envelope = String(backup, StandardCharsets.UTF_8).replace("\"version\":2", "\"version\":99")
        assertThrows(IllegalArgumentException::class.java) {
            CredentialBackupCodec.decrypt(envelope.toByteArray(StandardCharsets.UTF_8), passphrase)
        }
    }

    /** Version 1 envelopes predate the shared format rename and must still restore. */
    @Test
    fun versionOneEnvelopeRemainsReadable() {
        val legacy = mapOf("vault:opendrive/backupfile/token" to "legacy-secret")
        val backup = CredentialBackupCodec.encrypt(legacy, passphrase)
        val envelope = String(backup, StandardCharsets.UTF_8).replace("\"version\":2", "\"version\":1")
        assertEquals(legacy, CredentialBackupCodec.decrypt(envelope.toByteArray(StandardCharsets.UTF_8), passphrase))
    }
}
