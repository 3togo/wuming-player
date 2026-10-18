package com.wuming.android.tv.db

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "SearchHistory",
    indices = [Index(value = ["keyword"], unique = true), Index(value = ["timestamp"]), Index(value = ["frequency"])],
)
open class SearchHistory() {
    @field:PrimaryKey(autoGenerate = true)
    open var id: Int = 0
    open var keyword: String = ""
    open var timestamp: Long = System.currentTimeMillis()
    open var frequency: Int = 1

    @Ignore
    constructor(keyword: String) : this() {

        this.keyword = keyword
    }
}
