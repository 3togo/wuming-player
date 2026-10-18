package com.wuming.tv.core.cloud

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Naming and relocation rules for encrypted account-credential backups, shared by every platform.
 *
 * The directory itself is platform-specific (`/sdcard/<app>/credentials` on Android,
 * `~/.config/無名/credentials` on desktop), but the filename, the extension that marks a file as a
 * backup, and the migration rules are identical — they live here so the two platforms cannot drift.
 */
object CredentialBackupFiles {

    /** Sub-directory of the platform root that holds credential backups. */
    @JvmField
    val DIRECTORY_NAME = "credentials"

    /** A file is a credential backup when it carries this shared envelope extension. */
    @JvmField
    val FILE_SUFFIX = ".tvbox-credentials.json"

    /** A complete envelope is far larger than this; anything smaller is truncated or corrupt. */
    @JvmField
    val MIN_FILE_BYTES = 128L

    /** Display name for a new export: `無名-credentials-<yyyyMMdd-HHmmss>.tvbox-credentials.json`. */
    @JvmStatic
    @JvmOverloads
    fun suggestFilename(nowMillis: Long = System.currentTimeMillis()): String {
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))
        return "無名-credentials-$date$FILE_SUFFIX"
    }

    @JvmStatic
    fun isBackupFileName(name: String?): Boolean = name != null && name.endsWith(FILE_SUFFIX)

    /** Truncated or corrupt copies are discarded wherever they live, never restored. */
    @JvmStatic
    fun isTooSmall(sizeBytes: Long): Boolean = sizeBytes in 0 until MIN_FILE_BYTES

    /**
     * What to do with a backup found in a legacy directory. A legacy copy is relocated only while
     * its name is still unused by the target directory; a duplicate is discarded rather than
     * overwriting or renaming the copy the target already holds.
     */
    @JvmStatic
    fun relocation(sizeBytes: Long, nameTakenInTarget: Boolean): LegacyCredentialBackupAction = when {
        isTooSmall(sizeBytes) -> LegacyCredentialBackupAction.DISCARD
        nameTakenInTarget -> LegacyCredentialBackupAction.DISCARD
        else -> LegacyCredentialBackupAction.MOVE
    }
}

/** Outcome of [CredentialBackupFiles.relocation] for one legacy backup file. */
enum class LegacyCredentialBackupAction {
    /** Move the legacy copy into the current backup directory. */
    MOVE,

    /** Delete the legacy copy: too small to be valid, or already present in the target. */
    DISCARD,
}
