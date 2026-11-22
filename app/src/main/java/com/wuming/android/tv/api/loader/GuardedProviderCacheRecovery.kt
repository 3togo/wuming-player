package com.wuming.android.tv.api.loader

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Recovery for one measured Android native extraction, not application databases. */
internal class GuardedProviderCacheRecovery(
    private val providerSha256: String,
    private val payloadSha256: String,
) {
    private var attempted = false

    /** The caller must first establish the null-loader failure. Revalidate on retry. */
    @Synchronized
    fun recover(provider: File, codeCacheDir: File, initialize: () -> Unit): Boolean {
        if (attempted || Thread.currentThread().isInterrupted) return false
        if (!provider.isFile || GuardedProviderRecovery.digest(provider) != providerSha256) return false
        val root = codeCacheDir.canonicalFile
        val payload = File(root, "sharedb/config.jar")
        // Reject redirected directories/files: only this exact generated code is owned here.
        if (payload.canonicalFile != payload || !payload.isFile ||
            GuardedProviderRecovery.digest(payload) != payloadSha256
        ) return false
        val backup = File(payload.parentFile, "config.jar.loader-recovery-backup")
        if (Files.isSymbolicLink(backup.toPath())) return false
        // Reuse a backup only if it contains the identical generated payload. Never
        // overwrite an unrelated file, and keep the number of backups bounded.
        if (backup.exists() && (!backup.isFile || GuardedProviderRecovery.digest(backup) != payloadSha256)) return false
        Files.move(payload.toPath(), backup.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        attempted = true
        // Failure retains the backup and propagates to the existing loader guard.
        // Do not delete a replacement the native initializer may have generated.
        initialize()
        return true
    }
}
