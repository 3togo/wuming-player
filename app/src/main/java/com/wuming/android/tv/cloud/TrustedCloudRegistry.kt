package com.wuming.android.tv.cloud

import com.wuming.android.tv.cloud.opendrive.OpenDriveCacheCleaner

import java.util.EnumMap

class TrustedCloudRegistry private constructor() {

    companion object {
        private val ADAPTERS: MutableMap<CloudStorageProvider, TrustedCloudAdapter> =
            EnumMap<CloudStorageProvider, TrustedCloudAdapter>(CloudStorageProvider::class.java)

        init {
            register(OpenDriveAdapter(CloudStorageProvider.ALIPAN))
            register(OpenDriveAdapter(CloudStorageProvider.QUARK))
            register(OpenDriveAdapter(CloudStorageProvider.UC))
            register(OpenDriveAdapter(CloudStorageProvider.BAIDU))
            register(OpenDriveAdapter(CloudStorageProvider.PAN123))
            register(OpenDriveAdapter(CloudStorageProvider.TIANYI))
            register(PendingAdapter(CloudStorageProvider.DRIVE_115))
        }

        @JvmStatic
        fun get(provider: CloudStorageProvider): TrustedCloudAdapter? {
            return ADAPTERS.get(provider)
        }

        @JvmStatic
        fun register(adapter: TrustedCloudAdapter?) {
            if (adapter == null) {
                throw IllegalArgumentException("Trusted cloud adapter and provider are required")
            }
            ADAPTERS.put(adapter.provider(), adapter)
        }

        private data class OpenDriveAdapter(private val providerValue: CloudStorageProvider) : TrustedCloudAdapter {
            override fun provider(): CloudStorageProvider = providerValue

            override fun clear(records: List<@JvmSuppressWildcards CloudCacheRecord>): CloudStorageManager.Result {
                return OpenDriveCacheCleaner.clear(providerValue, records)
            }
        }

        private class PendingAdapter(private val providerValue: CloudStorageProvider) : TrustedCloudAdapter {
            override fun provider(): CloudStorageProvider {
                return providerValue
            }

            override fun clear(records: List<@JvmSuppressWildcards CloudCacheRecord>): CloudStorageManager.Result {
                return CloudStorageManager.Result.unsupported(
                    "A reviewed built-in adapter is not available for " + providerValue.displayName
                )
            }
        }
    }
}
