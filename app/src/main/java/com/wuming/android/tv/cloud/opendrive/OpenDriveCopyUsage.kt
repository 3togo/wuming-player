package com.wuming.android.tv.cloud.opendrive

import com.wuming.tv.core.cloud.OpenDrivePlayback
import com.wuming.tv.core.cloud.SavedCopyLeases
import com.wuming.tv.core.playback.OpenDrivePlaybackHints
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

object OpenDriveCopyUsage {
    private val LEASES = SavedCopyLeases(
        { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
        15 * 60_000L
    )

    @JvmStatic
    fun stage(provider: String, account: String, fid: String): String =
        LEASES.stage(SavedCopyLeases.Key(provider, account, fid))

    @JvmStatic
    fun attach(playback: OpenDrivePlayback, provider: String, account: String, fid: String): OpenDrivePlayback {
        if (account.isBlank()) return playback
        val headers = LinkedHashMap(playback.headers)
        headers[OpenDrivePlaybackHints.COPY_LEASE] = stage(provider, account, fid)
        return OpenDrivePlayback(playback.url, headers, playback.expiresAtEpochMillis)
    }

    @JvmStatic
    fun pin(headers: Map<String, String>?): AutoCloseable {
        if (headers != null) {
            for (entry in headers.entries) {
                if (OpenDrivePlaybackHints.COPY_LEASE.equals(entry.key, ignoreCase = true)) {
                    return LEASES.pin(entry.value)
                }
            }
        }
        return AutoCloseable { }
    }

    @JvmStatic
    fun busy(provider: String, fid: String): Boolean = LEASES.busy(provider, fid)

    @JvmStatic
    fun retire(provider: String, fid: String) = LEASES.retire(provider, fid)

    @JvmStatic
    @Throws(Exception::class)
    fun delete(provider: String, account: String, fid: String, action: SavedCopyLeases.Delete): Boolean =
        LEASES.delete(SavedCopyLeases.Key(provider, account, fid), action)

    class InUse : IOException("Cloud copy is in use")
}
