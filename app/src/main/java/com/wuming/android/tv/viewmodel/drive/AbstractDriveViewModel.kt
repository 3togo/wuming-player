package com.wuming.android.tv.viewmodel.drive

import com.wuming.android.tv.bean.DriveFolderFile
import com.wuming.android.tv.utils.Task
import java.text.Collator
import java.util.Locale
import java.util.concurrent.FutureTask

abstract class AbstractDriveViewModel : AutoCloseable {
    @JvmField
    protected var currentDrive: DriveFolderFile? = null
    @JvmField
    protected var currentDriveNote: DriveFolderFile? = null
    @JvmField
    protected var sortType: Int = 0
    private var loadGeneration: Int = 0
    private var loadTask: FutureTask<*>? = null
    private var closed: Boolean = false

    protected fun beginLoad(): Int {
        synchronized(this) {
            if (closed) return -1
            loadGeneration++
            loadTask?.cancel(true)
            loadTask = null
            return loadGeneration
        }
    }

    @Synchronized
    fun isCurrentLoad(generation: Int): Boolean = !closed && generation == loadGeneration

    protected fun isCurrent(generation: Int): Boolean =
        isCurrentLoad(generation) && !Thread.currentThread().isInterrupted()

    protected fun submitLoad(generation: Int, task: Runnable) {
        val future = object : FutureTask<Void>(task, null) {
            override fun done() {
                synchronized(this@AbstractDriveViewModel) {
                    if (loadTask === this) loadTask = null
                }
            }
        }
        synchronized(this) {
            if (!isCurrentLoad(generation)) return
            loadTask = future
        }
        Task.largeExecutor().execute(future)
    }

    fun getCurrentDrive(): DriveFolderFile? = currentDrive

    fun setCurrentDrive(currentDrive: DriveFolderFile?) {
        this.currentDrive = currentDrive
    }

    fun getCurrentDriveNote(): DriveFolderFile? = currentDriveNote

    fun setCurrentDriveNote(currentDriveNote: DriveFolderFile?) {
        this.currentDriveNote = currentDriveNote
    }

    fun setSortType(sortType: Int) {
        this.sortType = sortType
    }

    abstract fun loadData(callback: LoadDataCallback?): String?

    @Synchronized
    override fun close() {
        closed = true
        loadGeneration++
        loadTask?.cancel(true)
        loadTask = null
    }

    protected fun sortData(data: MutableList<DriveFolderFile>) {
        var backItem: DriveFolderFile? = null
        if (data.isNotEmpty() && data[0].name == null) {
            backItem = data.removeAt(0)
        }
        val requestedSort = sortType
        val comparator: Comparator<DriveFolderFile> = when (requestedSort) {
            2 -> Comparator.comparingLong { item -> modifiedTime(item) }
            3 -> Comparator { first, second -> java.lang.Long.compare(modifiedTime(second), modifiedTime(first)) }
            else -> {
                val collator = Collator.getInstance(Locale.CHINESE)
                collator.strength = Collator.SECONDARY
                Comparator { first, second ->
                    if (requestedSort == 1) collator.compare(name(second), name(first))
                    else collator.compare(name(first), name(second))
                }
            }
        }
        data.sortWith(comparator)
        if (backItem != null) data.add(0, backItem)
    }

    interface LoadDataCallback {
        fun callback(list: List<@JvmSuppressWildcards DriveFolderFile>, alreadyHasChildren: Boolean, generation: Int)
        fun fail(message: String, generation: Int)
    }

    companion object {
        private fun name(item: DriveFolderFile): String = item.name ?: ""
        private fun modifiedTime(item: DriveFolderFile): Long = item.lastModifiedDate ?: 0L
    }
}
