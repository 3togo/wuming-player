package com.wuming.android.tv.model

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.wuming.android.tv.Constant
import com.wuming.android.tv.api.SiteApi
import com.wuming.android.tv.api.SiteApiWithTracking
import com.wuming.android.tv.api.config.VodConfig
import com.wuming.android.tv.bean.Collect
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.bean.Site
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.exception.ExtractException
import com.wuming.android.tv.search.SearchResultAnnotator
import com.wuming.android.tv.search.TitleAliasRepository
import com.wuming.android.tv.search.TitleNormalizer
import com.wuming.android.tv.setting.DisplaySettings
import com.wuming.android.tv.utils.AppLog
import com.wuming.android.tv.utils.SearchCache
import com.wuming.android.tv.utils.SearchResultMerger
import com.wuming.android.tv.utils.Task
import com.wuming.tv.core.search.SearchQueryPlan
import com.wuming.tv.core.search.SearchQueryPlanner
import com.google.common.util.concurrent.FluentFuture
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListenableFutureTask
import com.google.common.util.concurrent.MoreExecutors
import java.util.EnumMap
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import java.util.function.Function

@JvmSuppressWildcards
open class SiteViewModel(
    private val gateway: ContentGateway,
    private val cacheLookup: Function<String, List<Collect>?>,
    private val queryPlanner: SearchQueryPlanner,
    private val strictSearch: BooleanSupplier,
) : ViewModel() {
    private val result = MutableLiveData<Result?>()
    private val player = MutableLiveData<Result?>()
    private val search = MutableLiveData<Result?>()
    private val action = MutableLiveData<Result?>()
    private val searchProgress = SearchProgress()
    private val searchEpoch = AtomicInteger(0)
    private val searchFuture = CopyOnWriteArrayList<Future<*>>()
    private val futures = EnumMap<TaskType, ListenableFuture<*>>(TaskType::class.java)
    private val taskIds = EnumMap<TaskType, AtomicInteger>(TaskType::class.java)

    // Request registration, completion and teardown share this instance monitor.
    private var cleared = false

    init {
        for (type in TaskType.entries) taskIds[type] = AtomicInteger(0)
    }
    constructor() : this(
        object : ContentGateway {
            override fun home(): Result? = SiteApi.homeContent(VodConfig.get().getHome())

            override fun category(
                key: String,
                tid: String,
                page: String,
                filter: Boolean,
                extend: HashMap<String, String>,
            ): Result? = SiteApi.categoryContent(key, tid, page, filter, extend)

            override fun action(
                key: String,
                act: String,
            ): Result? = SiteApi.action(key, act)

            override fun detail(
                key: String,
                id: String,
            ): Result? = SiteApi.detailContent(key, id)

            override fun player(
                key: String,
                flag: String,
                id: String,
            ): Result? = SiteApiWithTracking.playerContent(key, flag, id)

            override fun search(
                site: Site,
                keyword: String,
                quick: Boolean,
                page: String,
            ): Result? = SearchTask.create(site, keyword, quick, page).call()
        },
        Function { keyword ->
            SearchCache.get().get(keyword)
        },
        SearchQueryPlanner(TitleAliasRepository.get(), TitleNormalizer.core()),
        BooleanSupplier { DisplaySettings.isStrictSearch() },
    )
    constructor(
        gateway: ContentGateway,
        cacheLookup: Function<String, List<Collect>?>,
    ) : this(gateway, cacheLookup, SearchQueryPlanner.localOnly(TitleNormalizer.core()), BooleanSupplier { false })
    constructor(
        gateway: ContentGateway,
        cacheLookup: Function<String, List<Collect>?>,
        queryPlanner: SearchQueryPlanner,
    ) : this(gateway, cacheLookup, queryPlanner, BooleanSupplier { false })

    companion object {
        private const val LOGIN_ACTION = "LoginShow"

        // Login actions need the player timeout so QR polling can finish.
        @JvmStatic fun timeoutForAction(act: String?): Long = if (LOGIN_ACTION == act) Constant.TIMEOUT_PLAYER else Constant.TIMEOUT_VOD
    }

    open fun getResult(): LiveData<Result?> = result

    open fun getPlayer(): LiveData<Result?> = player

    open fun getSearch(): LiveData<Result?> = search

    open fun getAction(): LiveData<Result?> = action

    open fun clearPlayer(consumed: Result?) {
        if (player.value === consumed) player.value = null
    }

    open fun getSearchProgress(): LiveData<SearchProgress.ProgressInfo> = searchProgress.progress

    open fun init(): SiteViewModel {
        search.value = null
        result.value = null
        player.value = null
        action.value = null
        return this
    }

    open fun homeContent() {
        execute(TaskType.RESULT, result, gateway::home)
    }

    open fun categoryContent(
        key: String,
        tid: String,
        page: String,
        filter: Boolean,
        extend: HashMap<String, String>,
    ) {
        execute(TaskType.RESULT, result, { gateway.category(key, tid, page, filter, extend) })
    }

    open fun action(
        key: String,
        act: String,
    ) {
        execute(TaskType.ACTION, action, { gateway.action(key, act) }, timeoutForAction(act))
    }

    open fun detailContent(
        key: String,
        id: String,
    ) {
        execute(TaskType.RESULT, result, { gateway.detail(key, id) })
    }

    open fun playerContent(
        key: String,
        flag: String,
        id: String,
    ) {
        execute(TaskType.PLAYER, player, { gateway.player(key, flag, id) })
    }

    /** Pre-warm the spider cache without publishing to player LiveData. */
    open fun prewarmPlayerContent(
        key: String,
        flag: String,
        id: String,
    ) {
        execute(TaskType.PREWARM, null, { gateway.player(key, flag, id) })
    }

    open fun searchContent(
        site: Site,
        keyword: String,
        quick: Boolean,
        page: String,
    ) {
        execute(TaskType.RESULT, result, { gateway.search(site, keyword, quick, page) })
    }

    open fun searchContent(
        sites: List<Site>,
        keyword: String,
        quick: Boolean,
    ) {
        val epoch: Int
        val work = ListenableFutureTask.create(Callable { queryPlanner.plan(keyword) })
        val planning = FluentFuture.from(work)
        synchronized(this) {
            if (cleared) return
            epoch = stopSearch()
            val cached = cacheLookup.apply(keyword)
            if (cached != null) {
                for (collect in cached) search.postValue(Result.list(collect.getList()))
                searchProgress.start(sites)
                for (collect in cached) searchProgress.onSiteCompleted(collect.getSite().key)
                return
            }
            searchProgress.start(sites)
            searchFuture.add(planning)
        }
        planning.addCallback(
            object : FutureCallback<SearchQueryPlan> {
                override fun onSuccess(plan: SearchQueryPlan) {
                    synchronized(this@SiteViewModel) { searchFuture.remove(planning) }
                    launchExpandedSearch(sites, plan, quick, epoch)
                }

                override fun onFailure(error: Throwable) {
                    synchronized(this@SiteViewModel) {
                        searchFuture.remove(planning)
                        if (cleared || searchEpoch.get() != epoch || error is CancellationException) return
                    }
                    launchExpandedSearch(sites, SearchQueryPlanner.localOnly(TitleNormalizer.core()).plan(keyword), quick, epoch)
                }
            },
            MoreExecutors.directExecutor(),
        )
        Task.largeExecutor().execute(work)
    }

    private fun launchExpandedSearch(
        sites: List<Site>,
        plan: SearchQueryPlan,
        quick: Boolean,
        epoch: Int,
    ) {
        sites.forEach(
            java.util.function.Consumer { site ->
                val work = ListenableFutureTask.create(Callable { searchSite(site, plan, quick) })
                synchronized(this) {
                    if (cleared || searchEpoch.get() != epoch) return@Consumer
                    val future = FluentFuture.from(work).withTimeout(Constant.TIMEOUT_SEARCH, TimeUnit.MILLISECONDS, Task.scheduler())
                    searchFuture.add(future)
                    future.addCallback(
                        Task.callback<Result>(
                            { value ->
                                synchronized(this) {
                                    searchFuture.remove(future)
                                    if (!cleared && searchEpoch.get() == epoch) {
                                        search.postValue(value)
                                        searchProgress.onSiteCompleted(site.key)
                                    }
                                }
                            },
                            { _ ->
                                synchronized(this) {
                                    searchFuture.remove(future)
                                    if (!cleared && searchEpoch.get() == epoch) searchProgress.onSiteFailed(site.key)
                                }
                            },
                        ),
                        MoreExecutors.directExecutor(),
                    )
                }
                // Register before dispatch, but never dispatch under the owner lock:
                // a saturated pool can execute work inline.
                Task.largeExecutor().execute(work)
            },
        )
    }

    @Throws(Exception::class)
    private fun searchSite(
        site: Site,
        plan: SearchQueryPlan,
        quick: Boolean,
    ): Result {
        val items = ArrayList<Vod>()
        var lastFailure: Exception? = null
        var completedRequest = false
        var successfulRequests = 0
        var singleResult: Result? = null
        for (variant in plan.variants()) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Search canceled")
            try {
                val result = gateway.search(site, variant.text(), quick, "1")
                completedRequest = true
                successfulRequests++
                singleResult = if (successfulRequests == 1) result else null
                if (result == null || result.getList().isEmpty()) continue
                for (vod in result.getList()) if (vod.getSite() == null) vod.setSite(site)
                SearchResultAnnotator.annotate(result.getList(), plan, variant)
                if (strictSearch.asBoolean) SearchResultAnnotator.retainStrictMatches(result.getList(), plan, variant)
                items.addAll(result.getList())
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        if (!completedRequest && lastFailure != null) throw lastFailure
        if (successfulRequests == 1 && singleResult != null) return singleResult
        return Result.list(SearchResultMerger.merge(items))
    }

    private fun execute(
        type: TaskType,
        liveData: MutableLiveData<Result?>?,
        callable: Callable<Result?>,
    ) {
        execute(type, liveData, callable, type.timeout)
    }

    private fun execute(
        type: TaskType,
        liveData: MutableLiveData<Result?>?,
        callable: Callable<Result?>,
        timeout: Long,
    ) {
        val taskId: AtomicInteger
        val currentId: Int
        val work = ListenableFutureTask.create(callable)
        val future: FluentFuture<Result?>
        synchronized(this) {
            if (cleared) return
            taskId = taskIds[type]!!
            currentId = taskId.incrementAndGet()
            futures[type]?.cancel(true)
            future = FluentFuture.from(work).withTimeout(timeout, TimeUnit.MILLISECONDS, Task.scheduler())
            futures[type] = future
        }
        future.addCallback(
            Task.callback<Result?>(
                { value ->
                    synchronized(this) {
                        if (!cleared && taskId.get() == currentId) {
                            futures.remove(type, future)
                            liveData?.postValue(value)
                        }
                    }
                },
                { error ->
                    synchronized(this) {
                        if (!cleared && taskId.get() == currentId) {
                            futures.remove(type, future)
                            if (error !is CancellationException && liveData != null) {
                                val message = error.message ?: ""
                                val userVisible =
                                    error is ExtractException || (type == TaskType.PLAYER && message.isNotEmpty()) ||
                                        (type == TaskType.RESULT && message.isNotEmpty())
                                liveData.postValue(if (userVisible) Result.error(message) else Result.empty())
                                if (!userVisible) AppLog.e("SiteViewModel", "$type request failed", error)
                            }
                        }
                    }
                },
            ),
            MoreExecutors.directExecutor(),
        )
        Task.largeExecutor().execute(work)
    }

    @Synchronized open fun stopSearch(): Int {
        val epoch = searchEpoch.incrementAndGet()
        searchFuture.forEach { it.cancel(true) }
        searchFuture.clear()
        return epoch
    }

    @Synchronized override fun onCleared() {
        cleared = true
        taskIds.values.forEach { it.incrementAndGet() }
        stopSearch()
        futures.values.forEach { it.cancel(true) }
        futures.clear()
    }

    private enum class TaskType(
        val timeout: Long,
    ) {
        RESULT(Constant.TIMEOUT_VOD),
        PLAYER(Constant.TIMEOUT_PLAYER),
        ACTION(Constant.TIMEOUT_VOD),
        PREWARM(Constant.TIMEOUT_PLAYER),
    }

    interface ContentGateway {
        @Throws(Exception::class)
        fun home(): Result?

        @Throws(Exception::class)
        fun category(
            key: String,
            tid: String,
            page: String,
            filter: Boolean,
            extend: HashMap<String, String>,
        ): Result?

        @Throws(Exception::class)
        fun action(
            key: String,
            act: String,
        ): Result?

        @Throws(Exception::class)
        fun detail(
            key: String,
            id: String,
        ): Result?

        @Throws(Exception::class)
        fun player(
            key: String,
            flag: String,
            id: String,
        ): Result?

        @Throws(Exception::class)
        fun search(
            site: Site,
            keyword: String,
            quick: Boolean,
            page: String,
        ): Result?
    }
}
