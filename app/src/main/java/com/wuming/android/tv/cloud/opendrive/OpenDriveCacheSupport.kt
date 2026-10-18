package com.wuming.android.tv.cloud.opendrive

import com.github.catvod.net.PersistentCookieJar
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.cloud.CloudCacheRecord
import com.wuming.android.tv.cloud.CloudCacheRecord.Companion.create
import com.wuming.android.tv.cloud.CloudStorageProvider
import com.wuming.android.tv.cloud.CloudStorageRepository
import com.wuming.android.tv.cloud.CloudStorageRepository.Companion.active
import com.wuming.android.tv.cloud.CloudStorageRepository.Companion.markDeleted
import com.wuming.android.tv.cloud.CloudStorageRepository.Companion.record
import com.wuming.android.tv.cloud.opendrive.OpenDriveCopyUsage.busy
import com.wuming.android.tv.utils.Hex
import com.wuming.android.tv.utils.Hex.encodeLower
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Comparator
import java.util.function.ToLongFunction
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object OpenDriveCacheSupport {
    @JvmStatic
    fun accountHash(url: String): String {
        try {
            val values: MutableList<String> = ArrayList<String>()
            for (cookie: Cookie in PersistentCookieJar.get()
                .loadForRequest(url.toHttpUrlOrNull()!!)) {
                values.add(cookie.name + "=" + cookie.value)
            }
            values.sortWith(Comparator { obj: String?, s: String? -> obj!!.compareTo(s!!) })
            val digest: ByteArray = MessageDigest.getInstance("SHA-256")
                .digest(java.lang.String.join("\n", values).toByteArray(StandardCharsets.UTF_8))
            return encodeLower(digest)
        } catch (error: Exception) {
            throw IllegalStateException("Unable to identify the cloud account", error)
        }
    }

    @JvmStatic
    fun record(
        provider: CloudStorageProvider,
        accountUrl: String,
        folderId: String?,
        fileId: String?,
        name: String?,
        size: Long
    ): String {
        val accountHash: String = accountHash(accountUrl)
        CloudStorageRepository.record(
            create(
                provider,
                accountHash,
                folderId,
                fileId,
                name,
                VodConfig.OPEN_DRIVE_KEY,
                size
            )
        )
        return accountHash
    }

    @JvmStatic
    fun deleted(provider: CloudStorageProvider, accountHash: String?, fileId: String?) {
        if (accountHash == null ||
            accountHash.codePoints().allMatch { Character.isWhitespace(it) }
        ) {
            return
        }
        markDeleted(provider, accountHash, fileId)
    }

    /**
     * Reclaims disposable provider-side copies before another shared file is saved.
     * Only host-tracked records in the current cache folder are eligible; files attached to a
     * live playback session are explicitly protected.
     */
    @JvmStatic
    fun reclaimInactive(
        provider: CloudStorageProvider,
        folderId: String?,
        protectedFileIds: Set<String?>?,
        remoteDelete: RemoteDelete
    ): Int {
        val records: List<CloudCacheRecord?> = active(provider)
        var deleted: Int = 0
        for (record: CloudCacheRecord in reclaimable(
            records,
            provider,
            folderId,
            protectedFileIds
        )) {
            try {
                remoteDelete.delete(record.remoteFileId)
                deleted(provider, record.accountHash, record.remoteFileId)
                deleted++
            } catch (ignored: Exception) {
            }
        }
        return deleted
    }

    @JvmStatic
    fun reclaimable(
        records: List<CloudCacheRecord?>?,
        provider: CloudStorageProvider?,
        folderId: String?,
        protectedFileIds: Set<String?>?
    ): List<CloudCacheRecord> {
        if (records == null || records.isEmpty() || provider == null || folderId == null ||
            folderId.codePoints().allMatch { Character.isWhitespace(it) }
        ) {
            return emptyList()
        }
        val protectedIds: Set<String?> =
            if (protectedFileIds == null) mutableSetOf<String>() else protectedFileIds
        val result: MutableList<CloudCacheRecord> = ArrayList<CloudCacheRecord>()
        for (record: CloudCacheRecord? in records) {
            if (record == null || record.state != CloudCacheRecord.ACTIVE ||
                (provider.id != record.provider) ||
                (folderId != record.remoteFolderId) ||
                protectedIds.contains(
                    record.remoteFileId
                ) ||
                busy(provider.id, record.remoteFileId)
            ) {
                continue
            }
            result.add(record)
        }
        result.sortWith(
            Comparator.comparingLong<CloudCacheRecord?>(
                ToLongFunction { record: CloudCacheRecord? ->
                    record!!.createdAt
                }
            )
        )
        return result
    }

    fun interface RemoteDelete {
        @Throws(Exception::class)
        fun delete(fileId: String?)
    }
}
