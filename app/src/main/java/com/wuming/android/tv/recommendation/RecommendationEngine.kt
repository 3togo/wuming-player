package com.wuming.android.tv.recommendation

import com.wuming.android.tv.App
import com.wuming.android.tv.bean.ViewingRecord
import com.wuming.android.tv.bean.Vod
import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.utils.Task
import com.wuming.android.tv.library.UserLibrary
import com.wuming.tv.core.catalog.RecommendationCandidate
import com.wuming.tv.core.library.recommend.FavoriteSignal
import com.wuming.tv.core.library.recommend.RecommendationPolicy
import com.wuming.tv.core.library.recommend.RecommendationProfile
import com.google.common.util.concurrent.FluentFuture
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Local content-based recommendations learned from actual viewing duration. */
object RecommendationEngine {
    private val PROFILE_WINDOW_MS: Long = TimeUnit.DAYS.toMillis(365)
    private val PROFILE_CACHE_MS: Long = TimeUnit.MINUTES.toMillis(5)
    private const val MAX_RECORDS = 2000
    private val PROFILE_LOCK = Any()

    private var profileVersion: Long = 0
    private var profileSnapshot: ProfileSnapshot? = null
    private var profileFuture: ListenableFuture<ProfileSnapshot>? = null

    private data class ProfileSnapshot(
        val version: Long,
        val loadedAt: Long,
        val records: List<ViewingRecord>,
        val favorites: List<FavoriteSignal>,
    )

    @JvmStatic
    fun rankAsync(candidates: List<Vod>?, siteKey: String, callback: Consumer<List<Vod>>) {
        val copy: List<Vod> = candidates?.let { ArrayList(it) } ?: emptyList()
        if (!needsProfile(copy)) {
            App.post { callback.accept(copy) }
            return
        }
        FluentFuture.from(getProfile()).transform(
            { snapshot -> rank(copy, siteKey, snapshot.records, snapshot.favorites, System.currentTimeMillis()) },
            Task.executor(),
        ).addCallback(object : FutureCallback<List<Vod>> {
            override fun onSuccess(ranked: List<Vod>) {
                App.post { callback.accept(ranked) }
            }

            override fun onFailure(error: Throwable) {
                android.util.Log.e("RecommendationEngine", "Unable to rank recommendations", error)
                App.post { callback.accept(copy) }
            }
        }, MoreExecutors.directExecutor())
    }

    /** Starts loading the preference profile while configuration/content requests are in flight. */
    @JvmStatic
    fun prefetch() {
        getProfile()
    }

    /** Makes the next ranking request observe newly persisted viewing data. */
    @JvmStatic
    fun invalidate() {
        synchronized(PROFILE_LOCK) {
            profileVersion++
            profileSnapshot = null
            profileFuture = null
        }
    }

    @JvmStatic
    @JvmName("needsProfile")
    internal fun needsProfile(candidates: List<Vod>?): Boolean =
        candidates != null && candidates.size >= 2

    private fun getProfile(): ListenableFuture<ProfileSnapshot> {
        synchronized(PROFILE_LOCK) {
            val now = System.currentTimeMillis()
            val snapshot = profileSnapshot
            if (snapshot != null
                && snapshot.version == profileVersion
                && now - snapshot.loadedAt < PROFILE_CACHE_MS
            ) {
                return Futures.immediateFuture(snapshot)
            }
            profileFuture?.let { return it }
            val requestedVersion = profileVersion
            val callable = java.util.concurrent.Callable { loadProfile(requestedVersion) }
            val loading: ListenableFuture<ProfileSnapshot> = Task.executor().submit(callable)
            profileFuture = loading
            loading.addListener({ finishProfileLoad(loading, requestedVersion) }, MoreExecutors.directExecutor())
            return loading
        }
    }

    private fun loadProfile(version: Long): ProfileSnapshot {
        var records: List<ViewingRecord> = emptyList()
        var favorites: List<FavoriteSignal> = emptyList()
        try {
            records = AppDatabase.get().getViewingRecordDao()
                .findSince(System.currentTimeMillis() - PROFILE_WINDOW_MS, MAX_RECORDS)
        } catch (e: RuntimeException) {
            android.util.Log.e("RecommendationEngine", "Unable to load preference profile", e)
        }
        try {
            favorites = UserLibrary.get().getFavoriteSignals()
        } catch (e: RuntimeException) {
            android.util.Log.e("RecommendationEngine", "Unable to load favorites for profile", e)
        }
        return ProfileSnapshot(version, System.currentTimeMillis(), records, favorites)
    }

    private fun finishProfileLoad(loading: ListenableFuture<ProfileSnapshot>, version: Long) {
        synchronized(PROFILE_LOCK) {
            if (profileFuture !== loading || profileVersion != version) return
            try {
                profileSnapshot = loading.get()
            } catch (e: Exception) {
                android.util.Log.e("RecommendationEngine", "Unable to cache preference profile", e)
            } finally {
                profileFuture = null
            }
        }
    }

    @JvmStatic
    @JvmName("rank")
    internal fun rank(
        candidates: List<Vod>?,
        siteKey: String,
        records: List<ViewingRecord>?,
        favorites: List<FavoriteSignal>?,
        now: Long,
    ): List<Vod> {
        if (candidates == null || candidates.size < 2 || (records.isNullOrEmpty() && favorites.isNullOrEmpty())) {
            return candidates?.let { ArrayList(it) } ?: emptyList()
        }
        val profile: RecommendationProfile = RecommendationPolicy.buildProfile(records ?: emptyList(), now, favorites)
        if (!profile.hasSignal) return ArrayList(candidates)
        val adapted: List<VodCandidate> = candidates.map { VodCandidate(it) }
        val ranked: List<RecommendationCandidate> = RecommendationPolicy.rank(adapted, siteKey, profile)
        return ranked.map { (it as VodCandidate).source }
    }
}
