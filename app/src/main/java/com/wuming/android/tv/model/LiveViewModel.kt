package com.wuming.android.tv.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.wuming.android.tv.Constant
import com.wuming.android.tv.api.LiveApi
import com.wuming.android.tv.bean.Channel
import com.wuming.android.tv.bean.Epg
import com.wuming.android.tv.bean.EpgData
import com.wuming.android.tv.bean.Live
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.exception.ExtractException
import com.wuming.android.tv.utils.Task
import com.google.common.util.concurrent.FluentFuture
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListenableFutureTask
import com.google.common.util.concurrent.MoreExecutors
import java.time.ZoneId
import java.util.EnumMap
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

open class LiveViewModel : ViewModel() {
    private val epg = MutableLiveData<Epg>()
    private val xml = MutableLiveData<Boolean>()
    private val url = MutableLiveData<Result>()
    private val live = MutableLiveData<Live>()

    @Volatile private var zoneId = ZoneId.systemDefault()
    private val futures = EnumMap<TaskType, ListenableFuture<*>>(TaskType::class.java)
    private val taskIds = EnumMap<TaskType, AtomicInteger>(TaskType::class.java)
    private var cleared = false

    init {
        for (type in TaskType.entries) taskIds[type] = AtomicInteger(0)
    }

    open fun url(): LiveData<Result> = url

    open fun xml(): LiveData<Boolean> = xml

    open fun epg(): LiveData<Epg> = epg

    open fun live(): LiveData<Live> = live

    open fun getZoneId(): ZoneId = zoneId

    open fun parse(item: Live) {
        execute(
            TaskType.LIVE,
            Callable {
                LiveApi.parse(item)
                item
            },
            { parsed ->
                setTimeZone(parsed)
                live.postValue(parsed)
            },
            { live.postValue(Live()) },
        )
    }

    open fun parseXml(item: Live) {
        execute(TaskType.XML, Callable { LiveApi.parseXml(item) }, xml::postValue, { xml.postValue(false) })
    }

    open fun getEpg(item: Channel) {
        execute(TaskType.EPG, Callable { LiveApi.getEpg(item, zoneId) }, epg::postValue, { epg.postValue(Epg()) })
    }

    open fun getUrl(item: Channel) {
        execute(TaskType.URL, Callable { LiveApi.getUrl(item) }, url::postValue, ::handleUrlError)
    }

    open fun getUrl(
        item: Channel,
        data: EpgData,
    ) {
        execute(TaskType.URL, Callable { LiveApi.getUrl(item, data) }, url::postValue, ::handleUrlError)
    }

    private fun handleUrlError(error: Throwable) {
        url.postValue(if (error is ExtractException) Result.error(error.message) else Result())
    }

    private fun setTimeZone(live: Live) {
        zoneId =
            try {
                if (live.getTimeZone().isEmpty()) ZoneId.systemDefault() else ZoneId.of(live.getTimeZone())
            } catch (
                _: Exception,
            ) {
                ZoneId.systemDefault()
            }
    }

    private fun <T> execute(
        type: TaskType,
        callable: Callable<T>,
        onSuccess: Consumer<T>,
        onError: Consumer<Throwable>,
    ) {
        val taskId: AtomicInteger
        val currentId: Int
        val work = ListenableFutureTask.create(callable)
        val future: FluentFuture<T>
        synchronized(this) {
            if (cleared) return
            taskId = taskIds[type]!!
            currentId = taskId.incrementAndGet()
            futures[type]?.cancel(true)
            future = FluentFuture.from(work).withTimeout(type.timeout, TimeUnit.MILLISECONDS, Task.scheduler())
            futures[type] = future
        }
        future.addCallback(
            Task.callback<T>(
                { result ->
                    synchronized(this) {
                        if (!cleared && taskId.get() == currentId) {
                            futures.remove(type, future)
                            onSuccess.accept(result)
                        }
                    }
                },
                { error ->
                    synchronized(this) {
                        if (!cleared && taskId.get() == currentId) {
                            futures.remove(type, future)
                            if (error !is CancellationException) onError.accept(error)
                        }
                    }
                },
            ),
            MoreExecutors.directExecutor(),
        )
        Task.executor().execute(work)
    }

    @Synchronized override fun onCleared() {
        cleared = true
        taskIds.values.forEach { it.incrementAndGet() }
        futures.values.forEach { it.cancel(true) }
        futures.clear()
    }

    private enum class TaskType(
        val timeout: Long,
    ) {
        LIVE(Constant.TIMEOUT_LIVE),
        EPG(Constant.TIMEOUT_EPG),
        XML(Constant.TIMEOUT_XML),
        URL(Constant.TIMEOUT_PARSE_LIVE),
    }
}
