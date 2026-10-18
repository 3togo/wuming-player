package com.wuming.android.tv.repository

import com.wuming.android.tv.bean.History
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.db.DataCache
import com.wuming.android.tv.recommendation.RecommendationEngine
import com.wuming.android.tv.utils.Task
import com.wuming.tv.core.library.HistoryRepository

/** Android persistence adapter for history. Domain policy remains in user-library-core. */
object AndroidHistoryRepository : HistoryRepository<History> {
    @JvmStatic
    fun getInstance(): AndroidHistoryRepository = this

    override fun recent(cutoffTimeMillis: Long): List<History> =
        if (DataCache.isReady()) DataCache.get().findRecentHistories(cutoffTimeMillis)
        else AppDatabase.get().getHistoryDao().findRecent(cutoffTimeMillis)

    override fun recent(catalogId: Int, cutoffTimeMillis: Long): List<History> =
        if (DataCache.isReady()) DataCache.get().findHistoriesByCid(catalogId, cutoffTimeMillis)
        else AppDatabase.get().getHistoryDao().find(catalogId, cutoffTimeMillis)

    override fun findByKey(key: String): History? =
        if (DataCache.isReady()) DataCache.get().findHistory(key)
        else AppDatabase.get().getHistoryDao().findByKey(key)

    override fun findByName(name: String): List<History> {
        if (DataCache.isReady()) return DataCache.get().findHistoryByName(name)
        return try {
            AppDatabase.get().getHistoryDao().findByNameAll(name)
        } catch (error: Exception) {
            emptyList()
        }
    }

    override fun save(item: History) {
        if (DataCache.isReady()) DataCache.get().putHistory(item)
        else AppDatabase.get().getHistoryDao().insertOrUpdate(item)
    }

    override fun delete(catalogId: Int, key: String) {
        if (DataCache.isReady()) {
            DataCache.get().removeHistory(key)
        } else {
            AppDatabase.get().getHistoryDao().delete(catalogId, key)
            AppDatabase.get().getTrackDao().delete(key)
        }
    }

    override fun deleteCatalog(catalogId: Int) {
        if (DataCache.isReady()) {
            DataCache.get().clearHistoriesByCid(catalogId)
            Task.execute {
                AppDatabase.get().getViewingRecordDao().delete(catalogId)
                RecommendationEngine.invalidate()
            }
        } else {
            AppDatabase.get().getHistoryDao().delete(catalogId)
            AppDatabase.get().getViewingRecordDao().delete(catalogId)
            RecommendationEngine.invalidate()
        }
    }
}
