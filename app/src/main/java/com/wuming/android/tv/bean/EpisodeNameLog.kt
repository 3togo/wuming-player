package com.wuming.android.tv.bean

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "EpisodeNameLog",
    indices = [Index("isLong"), Index("timestamp"), Index(value = ["vodName", "episodeName"])]
)
data class EpisodeNameLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vodName: String,
    val episodeName: String,
    val flagName: String,
    val totalEpisodes: Int,
    val displayName: String,
    val displayLength: Int,
    val isLong: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)
