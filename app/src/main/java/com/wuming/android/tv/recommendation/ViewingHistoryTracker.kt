package com.wuming.android.tv.recommendation

import com.wuming.android.tv.bean.History
import com.wuming.android.tv.bean.ViewingRecord
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.repository.AndroidViewingRecordRepository
import com.wuming.android.tv.setting.AppSettings
import com.wuming.tv.core.library.ViewingProgressPolicy
import com.wuming.tv.core.library.ViewingRecordRepository

/** Records real elapsed play time, so seeking forward does not look like time watched. */
class ViewingHistoryTracker {
    private val repository: ViewingRecordRepository<ViewingRecord> = AndroidViewingRecordRepository.getInstance()
    private var record: ViewingRecord? = null
    private var lastElapsedTime: Long = 0
    private var lastSavedWatchTime: Long = 0

    fun update(
        history: History?,
        vod: Vod?,
        playing: Boolean,
        position: Long,
        duration: Long,
        elapsedTime: Long,
        wallTime: Long,
    ) {
        if (AppSettings.isIncognito() || history == null) {
            finish(wallTime, false)
            return
        }
        if (record != null && !record!!.matches(history)) finish(wallTime, false)
        if (record == null && !playing) return
        if (record == null) {
            record = ViewingRecord.create(history, vod, wallTime, position, duration)
            lastElapsedTime = elapsedTime
        }
        // A long callback gap usually means the UI was suspended; do not invent watch time.
        val delta = ViewingProgressPolicy.elapsedWatchTime(playing, lastElapsedTime, elapsedTime)
        lastElapsedTime = elapsedTime
        record!!.update(delta, wallTime, position, duration)
        if (ViewingProgressPolicy.shouldPersist(record!!.watchedDuration, lastSavedWatchTime)) persist()
    }

    fun finish(wallTime: Long, completed: Boolean) {
        val current = record ?: return
        current.finish(wallTime, completed)
        persist()
        record = null
        lastElapsedTime = 0
        lastSavedWatchTime = 0
    }

    private fun persist() {
        val current = record ?: return
        val snapshot = current.snapshot()
        lastSavedWatchTime = current.watchedDuration
        repository.save(snapshot)
    }
}
