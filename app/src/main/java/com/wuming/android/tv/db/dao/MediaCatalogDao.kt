package com.wuming.android.tv.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.wuming.android.tv.media.catalog.MediaResourceEntity
import com.wuming.android.tv.media.catalog.MediaSourceEntity

@Dao
@JvmSuppressWildcards
interface MediaCatalogDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertResource(resource: MediaResourceEntity): Unit

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertResource(resources: List<MediaResourceEntity>): Unit

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSources(sources: List<MediaSourceEntity>): Unit

    @Query("SELECT * FROM MediaResource ORDER BY createdAt ASC")
    fun findAllResources(): List<MediaResourceEntity>

    @Query("SELECT * FROM MediaSource ORDER BY createdAt ASC")
    fun findAllSources(): List<MediaSourceEntity>

    @Query("SELECT * FROM MediaResource WHERE uri = :uri LIMIT 1")
    fun findResource(uri: String?): MediaResourceEntity?

    @Query("SELECT * FROM MediaSource WHERE fingerprint = :fingerprint LIMIT 1")
    fun findSourceByFingerprint(fingerprint: String?): MediaSourceEntity?

    @Query(
        "SELECT * FROM MediaSource WHERE resourceUri = :resourceUri AND enabled = 1 " +
            "ORDER BY priority DESC, createdAt ASC",
    )
    fun findEnabledSources(resourceUri: String?): List<MediaSourceEntity>

    @Query("UPDATE MediaResource SET lastAccessedAt = :now WHERE uri = :uri")
    fun touch(
        uri: String?,
        now: Long,
    ): Unit

    @Query("UPDATE MediaResource SET pinned = 1, lastAccessedAt = :now WHERE uri = :uri")
    fun pin(
        uri: String?,
        now: Long,
    ): Unit

    @Query("SELECT COUNT(*) FROM MediaResource")
    fun countResources(): Int

    @Query(
        "SELECT uri FROM MediaResource WHERE pinned = 0 AND lastAccessedAt < :cutoff " +
            "AND uri NOT IN (SELECT mediaUri FROM History " +
            "WHERE mediaUri IS NOT NULL AND mediaUri != '') " +
            "ORDER BY lastAccessedAt ASC LIMIT :limit",
    )
    fun findGarbageCandidates(
        cutoff: Long,
        limit: Int,
    ): List<String>

    @Query(
        "SELECT uri FROM MediaResource WHERE pinned = 0 " +
            "AND uri NOT IN (SELECT mediaUri FROM History " +
            "WHERE mediaUri IS NOT NULL AND mediaUri != '') " +
            "ORDER BY lastAccessedAt ASC LIMIT :limit",
    )
    fun findOverflowCandidates(limit: Int): List<String>

    @Query("DELETE FROM MediaResource WHERE uri IN (:uris)")
    fun deleteResources(uris: List<String>): Int

    @Query(
        "SELECT credentialRef FROM MediaSource WHERE resourceUri IN (:resourceUris) " +
            "AND credentialRef != ''",
    )
    fun findCredentialRefs(resourceUris: List<String>): List<String>
}
