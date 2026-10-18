package com.wuming.android.tv.media.catalog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "MediaResource",
    indices = [Index(value = ["authority", "kind", "resourceId"], unique = true), Index(value = ["pinned", "lastAccessedAt"])],
)
open class MediaResourceEntity {
    @field:PrimaryKey @JvmField
    var uri: String = ""

    @JvmField var authority: String = ""

    @JvmField var kind: String = ""

    @JvmField var resourceId: String = ""

    @JvmField var title: String = ""

    @JvmField var manifestVersion: Int = 1

    @JvmField var createdAt: Long = 0

    @JvmField var updatedAt: Long = 0

    @JvmField var lastAccessedAt: Long = 0

    @JvmField var pinned: Boolean = false
}
