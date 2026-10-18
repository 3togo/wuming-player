package com.wuming.android.tv.cloud

import androidx.annotation.NonNull

import com.wuming.android.tv.App
import com.wuming.android.tv.utils.Task

import java.util.concurrent.Future

class CloudStorageManager private constructor() {

    interface Callback {
        fun onComplete(@NonNull result: Result)
    }

    class Result(
        @JvmField val supported: Boolean,
        @JvmField val deleted: Int,
        @JvmField val freedBytes: Long,
        message: String?,
        deletedIds: List<String>?,
        failedIds: List<String>,
        skippedIds: List<String>
    ) {
        @JvmField val message: String = message ?: ""
        @JvmField val deletedIds: List<String> = deletedIds?.toList() ?: emptyList()
        @JvmField val failedIds: List<String> = failedIds.toList()
        @JvmField val skippedIds: List<String> = skippedIds.toList()

        constructor(supported: Boolean, deleted: Int, freedBytes: Long, message: String) :
            this(supported, deleted, freedBytes, message, null, emptyList(), emptyList())

        constructor(supported: Boolean, deleted: Int, freedBytes: Long, message: String, deletedIds: List<String>) :
            this(supported, deleted, freedBytes, message, deletedIds, emptyList(), emptyList())

        fun successful(): Boolean = supported && this.failedIds.isEmpty()

        companion object {
            @JvmStatic
            fun unsupported(): Result = Result(false, 0, 0, "")

            @JvmStatic
            fun unsupported(message: String): Result = Result(false, 0, 0, message)
        }
    }

    companion object {
        @JvmField
        val CACHE_FOLDER: String = "TVBoxCache"

        @JvmStatic
        fun clearAppCache(provider: CloudStorageProvider?, callback: Callback?): Future<*> {
            if (provider == null || callback == null) {
                throw IllegalArgumentException("Provider and callback are required")
            }
            return Task.submitLarge {
                var result: Result
                try {
                    val records = CloudStorageRepository.active(provider)
                    val adapter = TrustedCloudRegistry.get(provider)
                    result = if (adapter == null)
                        Result.unsupported("No trusted adapter is registered")
                    else
                        validate(provider, records, adapter.clear(records))
                    if (result.supported) {
                        for (fileId in result.deletedIds) {
                            val record = find(records, fileId)
                            if (record != null) {
                                CloudStorageRepository.markDeleted(
                                    provider, record.accountHash, record.remoteFileId)
                            }
                        }
                    }
                } catch (error: Exception) {
                    val msg = error.message ?: error.javaClass.simpleName
                    result = Result.unsupported(msg)
                }
                CloudStorageRepository.audit(CloudAuditEvent.create(
                    provider, "clear_app_cache", result.successful(),
                    result.deleted, result.freedBytes,
                    "failed=" + result.failedIds.size + " skipped=" + result.skippedIds.size +
                        if (result.message.isEmpty()) "" else " " + result.message))
                post(callback, result)
            }
        }

        @JvmStatic
        fun validate(provider: CloudStorageProvider, records: List<CloudCacheRecord>, result: Result?): Result {
            if (result == null || !result.supported) {
                return result ?: Result.unsupported("Empty adapter result")
            }
            if (result.deleted != result.deletedIds.size) {
                return Result.unsupported("Adapter returned an unverifiable deletion count")
            }
            val unique = mutableSetOf<String>()
            for (outcome in listOf(result.deletedIds, result.failedIds, result.skippedIds)) {
                for (fileId in outcome) {
                    if (fileId.isEmpty() || !unique.add(fileId) || find(records, fileId) == null) {
                        return Result.unsupported(
                            "Adapter returned an untracked or conflicting " + provider.displayName + " outcome")
                    }
                }
            }
            return result
        }

        private fun find(records: List<CloudCacheRecord>, fileId: String): CloudCacheRecord? {
            for (record in records) {
                if (record.remoteFileId == fileId) return record
            }
            return null
        }

        private fun post(callback: Callback?, result: Result) {
            callback?.let { App.post { it.onComplete(result) } }
        }
    }
}
