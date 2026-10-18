package com.wuming.android.tv.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.wuming.android.tv.bean.Site
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SearchProgress {
    enum class Status { SEARCHING, COMPLETED, FAILED }

    private val progressLiveData = MutableLiveData<ProgressInfo>()
    private val siteStatus = ConcurrentHashMap<String, Status>()
    private val completedCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    val progress: LiveData<ProgressInfo> get() = progressLiveData

    fun start(sites: List<Site>?) {
        startKeys(sites?.map { it.key } ?: emptyList())
    }

    fun startKeys(siteKeys: List<String>) {
        siteStatus.clear()
        completedCount.set(0)
        totalCount.set(siteKeys.size)
        for (siteKey in siteKeys) siteStatus[siteKey] = Status.SEARCHING
        postProgress()
    }

    fun onSiteCompleted(siteKey: String?) = finish(siteKey, Status.COMPLETED)

    fun onSiteFailed(siteKey: String?) = finish(siteKey, Status.FAILED)

    private fun finish(siteKey: String?, status: Status) {
        if (siteKey != null && siteStatus.replace(siteKey, Status.SEARCHING, status)) completedCount.incrementAndGet()
        postProgress()
    }

    fun isComplete(): Boolean = completedCount.get() >= totalCount.get()

    fun getCompletedCount(): Int = completedCount.get()

    fun getTotalCount(): Int = totalCount.get()

    fun reset() {
        siteStatus.clear()
        completedCount.set(0)
        totalCount.set(0)
        progressLiveData.postValue(ProgressInfo(0, 0, false))
    }

    private fun postProgress() {
        val complete = isComplete()
        progressLiveData.postValue(ProgressInfo(completedCount.get(), totalCount.get(), complete))
    }

    data class ProgressInfo(
        val completed: Int,
        val total: Int,
        private val completeValue: Boolean,
    ) {
        fun isComplete(): Boolean = completeValue
        fun getComplete(): Boolean = completeValue
        val progressText: String
            get() = when {
                total == 0 -> ""
                completeValue -> "$completed/$total completed"
                else -> "Searching... $completed/$total"
            }
    }
}
