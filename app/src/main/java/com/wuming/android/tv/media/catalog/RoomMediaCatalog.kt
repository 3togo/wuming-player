package com.wuming.android.tv.media.catalog

import com.wuming.tv.core.media.catalog.MediaCatalogStore
import com.wuming.tv.core.media.catalog.MediaCatalogStore.SaveResult
import com.wuming.tv.core.media.catalog.MediaManifest

import android.database.sqlite.SQLiteConstraintException

import com.wuming.android.tv.db.AppDatabase
import com.wuming.android.tv.db.dao.MediaCatalogDao
import com.wuming.tv.core.media.DecodedMedia
import com.wuming.tv.core.media.MediaSourceDescriptor
import com.wuming.tv.core.media.OpenDriveUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

import java.lang.reflect.Type
import java.util.Optional
import java.util.UUID

/** Room-backed catalog. Call its synchronous methods from a worker thread. */
class RoomMediaCatalog(
    private val database: AppDatabase,
    private val gson: Gson
) : MediaCatalogStore {
    private val dao = database.getMediaCatalogDao()

    constructor() : this(AppDatabase.get(), Gson())

    @Synchronized
    override fun save(media: DecodedMedia): SaveResult {
        if (!media.persistable()) {
            throw IllegalArgumentException("Transient media sources cannot be persisted")
        }
        val existing = findExisting(media.sources)
        if (existing != null) return existing

        val uri = OpenDriveUri.local(media.kind)
        val now = System.currentTimeMillis()
        val resource = resource(uri, media, now)
        val sources = sources(uri, media.sources, now)
        try {
            database.runInTransaction {
                dao.insertResource(resource)
                dao.insertSources(sources)
            }
            prune()
            return SaveResult(uri, true)
        } catch (race: SQLiteConstraintException) {
            val raced = findExisting(media.sources)
            if (raced != null) return raced
            throw race
        }
    }

    override fun find(uri: OpenDriveUri): Optional<MediaManifest> {
        val resource = dao.findResource(uri.toString())
        if (resource == null) return Optional.empty()
        dao.touch(resource.uri, System.currentTimeMillis())

        val descriptors = ArrayList<MediaSourceDescriptor>()
        for (source in dao.findEnabledSources(resource.uri)) {
            try {
                val locator = gson.fromJson<Map<String, String>>(source.locatorJson, LOCATOR_TYPE)
                descriptors.add(MediaSourceDescriptor(source.adapterId, source.locatorVersion,
                        locator, source.fingerprint, source.priority, true, source.credentialRef))
            } catch (ignored: RuntimeException) {
            }
        }
        try {
            return Optional.of(MediaManifest(OpenDriveUri.parse(resource.uri), resource.title,
                    resource.manifestVersion, descriptors))
        } catch (invalidResource: RuntimeException) {
            return Optional.empty()
        }
    }

    override fun pin(uri: OpenDriveUri) {
        dao.pin(uri.toString(), System.currentTimeMillis())
    }

    @Synchronized
    override fun prune(): Int {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        val candidates = ArrayList(dao.findGarbageCandidates(cutoff, MAX_PRUNE_BATCH))
        val remainingOverflow = Math.max(0, dao.countResources() - candidates.size - MAX_RESOURCES)
        if (remainingOverflow > 0 && candidates.size < MAX_PRUNE_BATCH) {
            val overflow = dao.findOverflowCandidates(
                    Math.min(remainingOverflow, MAX_PRUNE_BATCH - candidates.size))
            for (uri in overflow) if (!candidates.contains(uri)) candidates.add(uri)
        }
        if (candidates.isEmpty()) return 0
        val credentialRefs = dao.findCredentialRefs(candidates)
        val deleted = dao.deleteResources(candidates)
        if (deleted > 0) {
            for (reference in credentialRefs) {
                com.wuming.android.tv.cloud.opendrive.OpenDriveVaultReferences.remove(reference)
            }
        }
        return deleted
    }

    private fun findExisting(sources: List<MediaSourceDescriptor>): SaveResult? {
        var resourceUri: String? = null
        for (descriptor in sources) {
            val existing = dao.findSourceByFingerprint(descriptor.fingerprint)
            if (existing == null) continue
            if (resourceUri != null && resourceUri != existing.resourceUri) {
                throw IllegalStateException("Media sources already belong to different resources")
            }
            resourceUri = existing.resourceUri
        }
        if (resourceUri == null) return null
        return SaveResult(OpenDriveUri.parse(resourceUri), false)
    }

    private fun resource(uri: OpenDriveUri, media: DecodedMedia, now: Long): MediaResourceEntity {
        val entity = MediaResourceEntity()
        entity.uri = uri.toString()
        entity.authority = uri.authority()
        entity.kind = uri.kind().value()
        entity.resourceId = uri.resourceId()
        entity.title = media.title
        entity.createdAt = now
        entity.updatedAt = now
        entity.lastAccessedAt = now
        return entity
    }

    private fun sources(uri: OpenDriveUri, descriptors: List<MediaSourceDescriptor>, now: Long): List<MediaSourceEntity> {
        val entities = ArrayList<MediaSourceEntity>(descriptors.size)
        for (descriptor in descriptors) {
            val entity = MediaSourceEntity()
            entity.sourceId = UUID.randomUUID().toString()
            entity.resourceUri = uri.toString()
            entity.adapterId = descriptor.adapterId
            entity.locatorVersion = descriptor.locatorVersion
            entity.locatorJson = gson.toJson(descriptor.locator, LOCATOR_TYPE)
            entity.credentialRef = descriptor.credentialRef
            entity.fingerprint = descriptor.fingerprint
            entity.priority = descriptor.priority
            entity.createdAt = now
            entity.updatedAt = now
            entities.add(entity)
        }
        return entities
    }

    companion object {
        private val LOCATOR_TYPE: Type = object : TypeToken<Map<String, String>>() {}.type
        private val RETENTION_MILLIS = java.util.concurrent.TimeUnit.DAYS.toMillis(90)
        private const val MAX_RESOURCES = 2_000
        private const val MAX_PRUNE_BATCH = 200
    }
}
