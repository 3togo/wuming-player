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
import javax.crypto.AEADBadTagException

object AccountCredentialBackup {

    /** The envelope decrypted but holds no restorable credential. */
    class EmptyBackupException : IllegalArgumentException("Backup contains no credentials")

    /** The passphrase is wrong: AES-GCM rejected the authentication tag. */
    class WrongPassphraseException(cause: Throwable) :
        IllegalArgumentException("Backup passphrase is wrong", cause)

    /** The file is unreadable or not a supported credential backup. */
    class MalformedBackupException(cause: Throwable) :
        IllegalArgumentException("Backup is not a valid account archive", cause)

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
            val count = AccountCredentialBackupState.credentialCount(values)
            // An archive without credentials would restore nothing yet still erase every
            // saved login, so an empty capture is never written.
            if (count == 0) return 0
            val encoded = CredentialBackupCodec.encrypt(values, passphrase)
            try {
                resolver.openOutputStream(uri, "wt")?.use { output: OutputStream ->
                    output.write(encoded)
                } ?: throw IllegalStateException("Unable to open backup destination")
            } finally {
                Arrays.fill(encoded, 0)
            }
            return count
        } finally {
            passphrase?.let { Arrays.fill(it, '\u0000') }
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun importFrom(resolver: ContentResolver, uri: Uri, passphrase: CharArray?): Int {
        try {
            val encoded: ByteArray = try {
                readBounded(resolver, uri)
            } catch (error: Exception) {
                throw MalformedBackupException(error)
            }
            val values: Map<String, String> = try {
                try {
                    CredentialBackupCodec.decrypt(encoded, passphrase)
                } catch (error: AEADBadTagException) {
                    throw WrongPassphraseException(error)
                } catch (error: Exception) {
                    throw MalformedBackupException(error)
                }
            } finally {
                Arrays.fill(encoded, 0)
            }
            val count = AccountCredentialBackupState.credentialCount(values)
            // Restoring replaces every stored credential, so an archive without any would
            // silently log the device out; treat it as a failed import instead.
            if (count == 0) throw EmptyBackupException()
            AccountCredentialBackupState.restore(values)
            return count
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
