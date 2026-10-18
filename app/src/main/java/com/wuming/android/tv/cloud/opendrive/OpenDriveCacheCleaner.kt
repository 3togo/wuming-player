package com.wuming.android.tv.cloud.opendrive

import com.wuming.android.tv.cloud.CloudCacheRecord
import com.wuming.android.tv.cloud.CloudStorageManager
import com.wuming.android.tv.cloud.CloudStorageProvider
import com.wuming.android.tv.cloud.opendrive.OpenDriveCopyUsage.InUse
import com.wuming.android.tv.cloud.opendrive.OpenDriveCopyUsage.busy
import java.util.ArrayList

/** Narrow bridge used only by the closed trusted-adapter registry.  */
@JvmSuppressWildcards
object OpenDriveCacheCleaner {
    @JvmStatic
    fun clear(
        provider: CloudStorageProvider?,
        records: List<CloudCacheRecord>
    ): CloudStorageManager.Result {
        if (provider != CloudStorageProvider.QUARK && provider != CloudStorageProvider.UC &&
            provider != CloudStorageProvider.ALIPAN
        ) {
            if (records.isEmpty()) {
                return CloudStorageManager.Result(
                    true,
                    0,
                    0,
                    "This provider streams without creating cloud copies",
                    mutableListOf<String>()
                )
            }
            return CloudStorageManager.Result(
                false,
                0,
                0,
                "This provider has no app-created cloud copies",
                mutableListOf<String>()
            )
        }
        val adapter: OpenDriveProvider = if (provider == CloudStorageProvider.QUARK) {
            QuarkOpenDriveProvider()
        } else {
            if (provider == CloudStorageProvider.UC) {
                UcOpenDriveProvider()
            } else {
                AliPanOpenDriveProvider()
            }
        }
        if (!adapter.authenticated()) {
            return CloudStorageManager.Result(
                false,
                0,
                0,
                "Sign in to " + provider.displayName + " before clearing",
                mutableListOf<String>()
            )
        }
        return collect(
            provider,
            records,
            TrackedDelete { record: CloudCacheRecord? ->
                if (adapter is QuarkOpenDriveProvider) {
                    return@TrackedDelete adapter.deleteTracked(record)
                } else if (adapter is UcOpenDriveProvider) {
                    return@TrackedDelete adapter.deleteTracked(record)
                } else {
                    return@TrackedDelete (adapter as AliPanOpenDriveProvider).deleteTracked(record)
                }
            }
        )
    }

    @JvmStatic
    fun collect(
        provider: CloudStorageProvider,
        records: List<CloudCacheRecord>,
        delete: TrackedDelete
    ): CloudStorageManager.Result {
        val deleted: MutableList<String> = ArrayList<String>()
        val failed: MutableList<String> = ArrayList<String>()
        val skipped: MutableList<String> = ArrayList<String>()
        var bytes: Long = 0L
        for (record: CloudCacheRecord in records) {
            if ((provider.id != record.provider) || record.state != CloudCacheRecord.ACTIVE || busy(
                    provider.id,
                    record.remoteFileId
                )
            ) {
                skipped.add(record.remoteFileId)
                continue
            }
            try {
                if (delete.delete(record)) {
                    deleted.add(record.remoteFileId)
                    bytes += record.sizeBytes
                } else {
                    skipped.add(record.remoteFileId)
                }
            } catch (busy: InUse) {
                skipped.add(record.remoteFileId)
            } catch (error: Exception) {
                // Retain failed records for a later retry; never persist raw provider errors.
                failed.add(record.remoteFileId)
            }
        }
        return CloudStorageManager.Result(true, deleted.size, bytes, "", deleted, failed, skipped)
    }

    fun interface TrackedDelete {
        @Throws(Exception::class)
        fun delete(record: CloudCacheRecord?): Boolean
    }
}
