package com.wuming.android.tv.utils

import com.wuming.android.tv.bean.SitePerformance
import com.wuming.android.tv.db.RoomSitePerformanceStore
import com.wuming.android.tv.db.SitePerformanceRepository
import com.wuming.android.tv.db.SitePerformanceStore
import com.wuming.tv.core.source.SitePersistencePolicy
import com.wuming.tv.core.source.SiteSpeedSamplePolicy
import com.wuming.tv.core.source.SourcePerformancePolicy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Function

/**
 * Non-blocking live performance records with asynchronous baseline hydration and batched writes.
 * Readers always receive live records; only detached snapshots are handed to the write-only port.
 */
open class SitePerformanceTracker(
    private val store: SitePerformanceStore,
    private val io: Executor,
    private val scheduler: ScheduledExecutorService,
    private val load: Function<String, SitePerformance?>
) {
    private val performanceCache = ConcurrentHashMap<String, SitePerformance>()
    private val persistenceLock = Any()
    private val hydrated = ConcurrentHashMap.newKeySet<SitePerformance>()
    private val lastSaveTime = AtomicLong()
    private val saveInProgress = AtomicBoolean()
    private val saveScheduled = AtomicBoolean()
    private val clearAllOperations = AtomicInteger()
    private val clearingKeys = ConcurrentHashMap<String, AtomicInteger>()
    private val dirtyKeys = ConcurrentHashMap.newKeySet<String>()
    private val failureCounts = ConcurrentHashMap<String, Int>()

    private constructor() : this(RoomSitePerformanceStore(), Task.executor(), Task.scheduler(),
        Function(SitePerformanceRepository::loadStored)) {
        scheduleSave(SitePersistencePolicy.INITIAL_SAVE_DELAY_MS)
    }

    /** Returns the same live record immediately; only IO tasks load its baseline. */
    private fun getOrCreatePerformance(siteKey: String): SitePerformance {
        performanceCache[siteKey]?.let { return it }
        if (performanceCache.size >= MAX_TRACKED_KEYS) {
            performanceCache.clear()
            hydrated.clear()
            dirtyKeys.clear()
            failureCounts.clear()
        }
        val value = SitePerformance(siteKey)
        performanceCache.putIfAbsent(siteKey, value)?.let { return it }
        // A record created during clear belongs to the new generation.
        if (isClearing(siteKey)) hydrated.add(value)
        io.execute {
            try { hydrate(siteKey, value) }
            catch (failure: Exception) { AppLog.w(TAG, "Performance hydration deferred", failure) }
        }
        return value
    }

    private fun hydrate(siteKey: String, value: SitePerformance) {
        if (performanceCache[siteKey] !== value || hydrated.contains(value) || isClearing(siteKey)) return
        val baseline = load.apply(siteKey)
        synchronized(persistenceLock) {
            if (performanceCache[siteKey] !== value || hydrated.contains(value) || isClearing(siteKey)) return
            synchronized(value) {
                if (baseline != null) value.adoptBaseline(baseline)
                hydrated.add(value)
            }
        }
    }

    open fun recordSuccess(siteKey: String?, speedBytesPerSec: Long) {
        if (siteKey.isNullOrEmpty()) return
        AppLog.d(TAG, "recordSuccess: siteKey=$siteKey, speed=$speedBytesPerSec")
        getOrCreatePerformance(siteKey).recordSuccess(speedBytesPerSec)
        markDirty(siteKey)
        checkAndSaveToDatabase()
    }

    open fun recordSuccess(siteKey: String?) { recordSuccess(siteKey, 0L) }

    open fun recordDeadLink(siteKey: String?) {
        if (siteKey.isNullOrEmpty()) return
        AppLog.d(TAG, "recordDeadLink: siteKey=$siteKey")
        getOrCreatePerformance(siteKey).recordDeadLink()
        markDirty(siteKey)
        checkAndSaveToDatabase()
    }

    open fun recordFailure(siteKey: String?) {
        if (siteKey.isNullOrEmpty()) return
        AppLog.d(TAG, "recordFailure: siteKey=$siteKey")
        getOrCreatePerformance(siteKey).recordFailure()
        markDirty(siteKey)
        checkAndSaveToDatabase()
    }

    open fun getPerformance(siteKey: String?): SitePerformance? =
        if (siteKey.isNullOrEmpty()) null else getOrCreatePerformance(siteKey)

    private fun markDirty(siteKey: String) { dirtyKeys.add(siteKey) }

    private fun checkAndSaveToDatabase() {
        if (dirtyKeys.isEmpty()) return
        val remaining = SitePersistencePolicy.remainingMillis(System.currentTimeMillis(), lastSaveTime.get())
        if (remaining > 0) { scheduleSave(remaining); return }
        if (!saveInProgress.compareAndSet(false, true)) return
        io.execute {
            try {
                doSaveAllToDatabase()
                lastSaveTime.set(System.currentTimeMillis())
            } finally {
                saveInProgress.set(false)
                if (dirtyKeys.isNotEmpty()) scheduleSave(SitePersistencePolicy.SAVE_INTERVAL_MS)
            }
        }
    }

    private fun scheduleSave(delayMs: Long) {
        if (!saveScheduled.compareAndSet(false, true)) return
        scheduler.schedule({
            saveScheduled.set(false)
            checkAndSaveToDatabase()
        }, maxOf(0L, delayMs), TimeUnit.MILLISECONDS)
    }

    private fun doSaveAllToDatabase() {
        val toSave = ArrayList(dirtyKeys)
        if (toSave.isEmpty()) { AppLog.v(TAG, "No dirty entries to save"); return }
        var savedCount = 0
        for (siteKey in toSave) {
            dirtyKeys.remove(siteKey)
            if (isClearing(siteKey)) { dirtyKeys.add(siteKey); continue }
            val perf = performanceCache[siteKey] ?: continue
            try {
                hydrate(siteKey, perf)
                val persisted = synchronized(persistenceLock) {
                    // A clear operation may have removed this entry while the save was queued.
                    if (isClearing(siteKey) || performanceCache[siteKey] !== perf) {
                        if (performanceCache[siteKey] === perf) dirtyKeys.add(siteKey)
                        return@synchronized false
                    }
                    if (!hydrated.contains(perf)) { dirtyKeys.add(siteKey); return@synchronized false }
                    store.persist(perf.snapshot())
                    true
                }
                if (!persisted) continue
                failureCounts.remove(siteKey)
                savedCount++
            } catch (failure: Exception) {
                AppLog.e(TAG, "Error saving site $siteKey", failure)
                val failures = failureCounts.getOrDefault(siteKey, 0) + 1
                if (SitePersistencePolicy.shouldRetry(failures)) {
                    dirtyKeys.add(siteKey)
                    failureCounts[siteKey] = failures
                } else {
                    AppLog.w(TAG, "Dropping key $siteKey after $failures save failures")
                    failureCounts.remove(siteKey)
                }
            }
        }
        if (savedCount > 0) AppLog.d(TAG, "Saved $savedCount / ${toSave.size} dirty records")
    }

    open fun startSpeedMeasurement(siteKey: String?): SpeedMeasurer = SpeedMeasurer(siteKey)

    open class SpeedMeasurer internal constructor(private val siteKey: String?) {
        private val startTime = System.currentTimeMillis()
        private val totalBytes = AtomicLong()

        open fun addBytes(bytes: Long) { if (bytes > 0) totalBytes.addAndGet(bytes) }
        open fun complete(success: Boolean) { if (success) completeSuccess() else completeFailed() }

        open fun completeSuccess() {
            val duration = System.currentTimeMillis() - startTime
            when (SiteSpeedSamplePolicy.outcome(totalBytes.get(), duration)) {
                SiteSpeedSamplePolicy.Outcome.DEAD_LINK -> get().recordDeadLink(siteKey)
                SiteSpeedSamplePolicy.Outcome.UNMEASURED_SUCCESS -> get().recordSuccess(siteKey, 0L)
                SiteSpeedSamplePolicy.Outcome.MEASURED_SUCCESS -> get().recordSuccess(siteKey, calculateSpeed(duration))
            }
        }

        open fun completeDeadLink() { get().recordDeadLink(siteKey) }
        open fun completeFailed() { get().recordFailure(siteKey) }
        open fun getCurrentSpeed(): Long = SiteSpeedSamplePolicy.liveBytesPerSecond(totalBytes.get(), System.currentTimeMillis() - startTime)
        open fun getFormattedSpeed(): String = SourcePerformancePolicy.formatSpeed(getCurrentSpeed().toDouble(), Locale.getDefault())
        private fun calculateSpeed(durationMs: Long): Long = SiteSpeedSamplePolicy.bytesPerSecond(totalBytes.get(), durationMs)
    }

    open fun clearAll() {
        clearAllOperations.incrementAndGet()
        performanceCache.clear()
        hydrated.clear()
        dirtyKeys.clear()
        failureCounts.clear()
        store.evictAll()
        io.execute {
            try { synchronized(persistenceLock) { store.deleteAll() } }
            catch (failure: Exception) { AppLog.e(TAG, "Error clearing database", failure) }
            finally {
                clearAllOperations.decrementAndGet()
                checkAndSaveToDatabase()
            }
        }
    }

    open fun clear(siteKey: String?) {
        if (siteKey.isNullOrEmpty()) return
        clearingKeys.compute(siteKey) { _, count ->
            if (count == null) AtomicInteger(1) else count.also { it.incrementAndGet() }
        }
        performanceCache.remove(siteKey)?.let { hydrated.remove(it) }
        dirtyKeys.remove(siteKey)
        failureCounts.remove(siteKey)
        store.evict(siteKey)
        io.execute {
            try { synchronized(persistenceLock) { store.delete(siteKey) } }
            catch (failure: Exception) { AppLog.e(TAG, "Error clearing site from database: $siteKey", failure) }
            finally {
                clearingKeys.computeIfPresent(siteKey) { _, count -> if (count.decrementAndGet() == 0) null else count }
                checkAndSaveToDatabase()
            }
        }
    }

    private fun isClearing(siteKey: String): Boolean = clearAllOperations.get() > 0 || clearingKeys.containsKey(siteKey)

    open fun forceSave() {
        io.execute {
            try { doSaveAllToDatabase() }
            catch (failure: Exception) { AppLog.e(TAG, "Error during forceSave", failure) }
        }
    }

    companion object {
        private const val TAG = "SitePerformanceTracker"
        private const val MAX_TRACKED_KEYS = 512
        @Volatile private var instance: SitePerformanceTracker? = null

        @JvmStatic
        fun get(): SitePerformanceTracker {
            if (instance == null) synchronized(SitePerformanceTracker::class.java) {
                if (instance == null) instance = SitePerformanceTracker()
            }
            return instance!!
        }
    }
}
