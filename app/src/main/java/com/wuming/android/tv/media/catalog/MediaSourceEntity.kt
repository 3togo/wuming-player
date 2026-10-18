package com.wuming.android.tv.media.catalog

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "MediaSource",
    foreignKeys = [
        ForeignKey(
            entity = MediaResourceEntity::class,
            parentColumns = ["uri"],
            childColumns = ["resourceUri"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["resourceUri"]), Index(value = ["fingerprint"], unique = true), Index(value = ["adapterId", "enabled"])],
)
open class MediaSourceEntity {
    @field:PrimaryKey @JvmField
    var sourceId: String = ""

    @JvmField var resourceUri: String = ""

    @JvmField var adapterId: String = ""

    @JvmField var locatorVersion: Int = 0

    @JvmField var locatorJson: String = ""

    @JvmField var credentialRef: String = ""

    @JvmField var fingerprint: String = ""

    @JvmField var priority: Int = 0

    @JvmField var enabled: Boolean = true

    @JvmField var createdAt: Long = 0

    @JvmField var updatedAt: Long = 0
}
