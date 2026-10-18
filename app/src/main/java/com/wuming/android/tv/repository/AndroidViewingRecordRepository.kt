package com.wuming.android.tv.repository

import com.wuming.android.tv.bean.ViewingRecord
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.recommendation.RecommendationEngine
import com.wuming.android.tv.utils.Task
import com.wuming.tv.core.library.ViewingRecordRepository

/** Serializes Room writes and recommendation invalidation for viewing sessions. */
object AndroidViewingRecordRepository : ViewingRecordRepository<ViewingRecord> {
    @JvmStatic
    fun getInstance(): AndroidViewingRecordRepository = this

    override fun save(record: ViewingRecord) {
        Task.scheduler().execute {
            AppDatabase.get().getViewingRecordDao().insertOrUpdate(record)
            RecommendationEngine.invalidate()
        }
    }
}
