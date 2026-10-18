package com.wuming.android.tv.cloud

import com.wuming.android.tv.db.AppDatabase

class CloudStorageRepository private constructor() {

    companion object {
        @JvmStatic
        fun record(record: CloudCacheRecord): Long {
            validate(record)
            return AppDatabase.get().getCloudCacheDao().insert(record)
        }

        @JvmStatic
        fun active(provider: CloudStorageProvider): List<CloudCacheRecord> {
            return AppDatabase.get().getCloudCacheDao().active(provider.id)
        }

        @JvmStatic
        fun records(provider: CloudStorageProvider?, accountHash: String?, folderId: String?): List<CloudCacheRecord> {
            if (provider == null || empty(accountHash) || empty(folderId)) return emptyList()
            return AppDatabase.get().getCloudCacheDao().records(
                provider.id, accountHash, folderId
            )
        }

        @JvmStatic
        fun markDeleted(provider: CloudStorageProvider, accountHash: String?, remoteFileId: String?) {
            if (empty(accountHash) || empty(remoteFileId)) return
            AppDatabase.get().getCloudCacheDao().updateState(
                provider.id, accountHash, remoteFileId,
                CloudCacheRecord.DELETED, System.currentTimeMillis()
            )
        }

        @JvmStatic
        fun audit(event: CloudAuditEvent) {
            AppDatabase.get().getCloudAuditDao().insert(event)
        }

        @JvmStatic
        fun stats(provider: CloudStorageProvider): Stats {
            val records = active(provider)
            var bytes = 0L
            for (record in records) bytes += Math.max(0L, record.sizeBytes)
            return Stats(records.size, bytes)
        }

        private fun validate(record: CloudCacheRecord?) {
            if (record == null
                || empty(record.provider)
                || empty(record.accountHash)
                || empty(record.remoteFolderId)
                || empty(record.remoteFileId)
            ) {
                throw IllegalArgumentException(
                    "Provider, account hash, folder ID, and file ID are required"
                )
            }
            var knownProvider = false
            for (provider in CloudStorageProvider.entries) {
                if (provider.id == record.provider) knownProvider = true
            }
            if (!knownProvider) throw IllegalArgumentException("Unknown cloud provider")
            if (record.sizeBytes < 0) throw IllegalArgumentException("Negative cache size")
        }

        private fun empty(value: String?): Boolean {
            return value == null || value.trim().isEmpty()
        }
    }

    class Stats(count: Int, bytes: Long) {
        val count: Int = Math.max(0, count)
        val bytes: Long = Math.max(0L, bytes)
    }
}
