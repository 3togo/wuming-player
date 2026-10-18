package com.wuming.android.tv.cloud

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Host-owned remote copy. Only opaque IDs belong here, never credentials or URLs. */
@Entity(
    tableName = "CloudCacheRecord",
    indices = [Index(value = ["provider", "accountHash", "remoteFileId"], unique = true), Index(value = ["provider", "state"])],
)
open class CloudCacheRecord {
    @field:PrimaryKey(autoGenerate = true)
    @JvmField
    var id: Long = 0

    @JvmField var provider: String = ""

    @JvmField var accountHash: String = ""

    @JvmField var remoteFolderId: String = ""

    @JvmField var remoteFileId: String = ""

    @JvmField var displayName: String = ""

    @JvmField var sourceSiteKey: String = ""

    @JvmField var sizeBytes: Long = 0

    @JvmField var state: Int = ACTIVE

    @JvmField var createdAt: Long = 0

    @JvmField var updatedAt: Long = 0

    companion object {
        const val ACTIVE = 0
        const val DELETED = 1
        const val FAILED = 2

        @JvmStatic fun create(
            provider: CloudStorageProvider,
            accountHash: String?,
            folderId: String?,
            fileId: String?,
            name: String?,
            siteKey: String?,
            sizeBytes: Long,
        ): CloudCacheRecord =
            CloudCacheRecord().also {
                it.provider = provider.id
                it.accountHash = accountHash ?: ""
                it.remoteFolderId = folderId ?: ""
                it.remoteFileId = fileId ?: ""
                it.displayName = name ?: ""
                it.sourceSiteKey = siteKey ?: ""
                it.sizeBytes = maxOf(0L, sizeBytes)
                it.updatedAt = System.currentTimeMillis()
                it.createdAt = it.updatedAt
            }
    }
}
