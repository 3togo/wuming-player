package com.wuming.android.tv.cloud

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only audit event for cloud-cache discovery and deletion attempts. */
@Entity(tableName = "CloudAuditEvent", indices = [Index(value = ["provider", "createdAt"])])
open class CloudAuditEvent {
    @field:PrimaryKey(autoGenerate = true)
    @JvmField
    var id: Long = 0

    @JvmField var provider: String = ""

    @JvmField var operation: String = ""

    @JvmField var success: Boolean = false

    @JvmField var itemCount: Int = 0

    @JvmField var bytes: Long = 0

    @JvmField var message: String = ""

    @JvmField var createdAt: Long = 0

    companion object {
        @JvmStatic fun create(
            provider: CloudStorageProvider,
            operation: String?,
            success: Boolean,
            itemCount: Int,
            bytes: Long,
            message: String?,
        ): CloudAuditEvent =
            CloudAuditEvent().also {
                it.provider = provider.id
                it.operation = operation ?: ""
                it.success = success
                it.itemCount = maxOf(0, itemCount)
                it.bytes = maxOf(0L, bytes)
                it.message = message ?: ""
                it.createdAt = System.currentTimeMillis()
            }
    }
}
