package com.wuming.android.tv.cloud.opendrive

import android.content.ContentResolver
import android.net.Uri
import com.wuming.tv.core.cloud.CredentialBackupCodec
import com.wuming.tv.core.cloud.CredentialBackupFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays

object AccountCredentialBackup {

    @JvmStatic
    fun suggestFilename(): String = CredentialBackupFiles.suggestFilename()

    @JvmStatic
    fun resolve(input: String?): File {
        var path = input?.trim() ?: ""
        require(path.isNotEmpty() && !path.contains("://") && path.indexOf('\u0000') < 0) {
            "Enter a local file path"
        }
        if (path == "sdcard" || path.startsWith("sdcard/")) path = "/$path"
        val file = File(path)
        return if (file.isAbsolute) file else File("/sdcard", path)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun exportTo(resolver: ContentResolver, uri: Uri, passphrase: CharArray?): Int {
        try {
            val values = AccountCredentialBackupState.capture()
            val encoded = CredentialBackupCodec.encrypt(values, passphrase)
            try {
                resolver.openOutputStream(uri, "wt")?.use { output: OutputStream ->
                    output.write(encoded)
                } ?: throw IllegalStateException("Unable to open backup destination")
            } finally {
                Arrays.fill(encoded, 0)
            }
            return AccountCredentialBackupState.credentialCount(values)
        } finally {
            passphrase?.let { Arrays.fill(it, '\u0000') }
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun importFrom(resolver: ContentResolver, uri: Uri, passphrase: CharArray?): Int {
        try {
            val encoded = readBounded(resolver, uri)
            val values: Map<String, String>
            try {
                values = CredentialBackupCodec.decrypt(encoded, passphrase)
            } finally {
                Arrays.fill(encoded, 0)
            }
            AccountCredentialBackupState.restore(values)
            return AccountCredentialBackupState.credentialCount(values)
        } finally {
            passphrase?.let { Arrays.fill(it, '\u0000') }
        }
    }

    @Throws(Exception::class)
    private fun readBounded(resolver: ContentResolver, uri: Uri): ByteArray {
        resolver.openInputStream(uri)?.use { input: InputStream ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    total += read
                    if (total > CredentialBackupCodec.MAX_FILE_BYTES) {
                        throw IllegalArgumentException("Backup is too large")
                    }
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
        } ?: throw IllegalStateException("Unable to open backup")
    }
}
