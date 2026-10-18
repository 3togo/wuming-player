package com.wuming.android.tv.db.dao
import androidx.room.Dao
import androidx.room.Query
import com.wuming.android.tv.bean.SitePerformance

@Dao
abstract class SitePerformanceDao : BaseDao<SitePerformance>() {
    @Query("SELECT * FROM SitePerformance")
    abstract fun findAll(): List<SitePerformance>

    @Query("SELECT * FROM SitePerformance WHERE siteKey = :siteKey LIMIT 1")
    abstract fun findBySiteKey(siteKey: String?): SitePerformance?

    @Query("SELECT * FROM SitePerformance ORDER BY performanceScore DESC")
    abstract fun findAllOrderByPerformance(): List<SitePerformance>

    @Query("SELECT * FROM SitePerformance ORDER BY reliabilityScore DESC")
    abstract fun findAllOrderByReliability(): List<SitePerformance>

    @Query("SELECT * FROM SitePerformance ORDER BY avgSpeed DESC")
    abstract fun findAllOrderBySpeed(): List<SitePerformance>

    @Query("SELECT * FROM SitePerformance WHERE performanceScore >= :minScore ORDER BY performanceScore DESC")
    abstract fun findByMinPerformanceScore(minScore: Double): List<SitePerformance>

    @Query("SELECT * FROM SitePerformance WHERE reliabilityScore >= :minScore ORDER BY reliabilityScore DESC")
    abstract fun findByMinReliabilityScore(minScore: Double): List<SitePerformance>

    @Query("DELETE FROM SitePerformance WHERE siteKey = :siteKey")
    abstract fun deleteBySiteKey(siteKey: String?): Unit

    @Query("DELETE FROM SitePerformance")
    abstract fun deleteAll(): Unit

    open fun getOrCreate(siteKey: String?): SitePerformance =
        findBySiteKey(siteKey) ?: SitePerformance(siteKey!!).also {
            insert(it)
        }

    open fun recordSuccess(
        siteKey: String?,
        speedBytesPerSec: Long,
    ) {
        val p = getOrCreate(siteKey)
        p.recordSuccess(speedBytesPerSec)
        update(p)
    }

    open fun recordDeadLink(siteKey: String?) {
        val p = getOrCreate(siteKey)
        p.recordDeadLink()
        update(p)
    }

    open fun recordFailure(siteKey: String?) {
        val p = getOrCreate(siteKey)
        p.recordFailure()
        update(p)
    }
}
