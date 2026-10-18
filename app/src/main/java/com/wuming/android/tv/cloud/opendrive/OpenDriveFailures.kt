package com.wuming.android.tv.cloud.opendrive

import com.wuming.tv.core.cloud.CloudStorageEngine
import com.wuming.tv.core.cloud.OpenDriveFailure
import com.wuming.tv.core.cloud.OpenDriveFailurePolicy
import com.wuming.tv.core.cloud.OpenDrivePlayback

/** Android exception/diagnostic ports; classification and auth gates are shared. */
internal object OpenDriveFailures {
    val STATUSES = OpenDriveFailurePolicy.StatusPort { error ->
        when (error) {
            is QuarkNetwork.QuarkQuotaException -> OpenDriveFailurePolicy.Status(quota = true)
            is OpenDriveHttp.StatusException -> OpenDriveFailurePolicy.Status(error.code())
            is QuarkNetwork.QuarkHttpException -> OpenDriveFailurePolicy.Status(error.code(), error.apiCode())
            else -> null
        }
    }
    val DIAGNOSTICS = object : CloudStorageEngine.Diagnostics {
        override fun unavailable(provider: String, message: String) = OpenDriveLog.providerUnavailable(provider, message)
        override fun authExpired(provider: String) = OpenDriveLog.authExpired(provider)
        override fun failed(provider: String, item: String, error: Exception) {
            if (error is OpenDriveFailure) OpenDriveDiagnostics.failure(error)
            OpenDriveLog.resolveFailure(provider, item, error)
        }
        override fun proxied(provider: String, item: String, kind: String) = OpenDriveLog.proxiedCloudContent(provider, item, kind)
    }

    @JvmStatic
    @Throws(Exception::class)
    fun resolve(provider: OpenDriveProvider, itemId: String): OpenDrivePlayback =
        CloudStorageEngine(listOf(provider), STATUSES, DIAGNOSTICS).resolve(provider.id(), itemId)

    @JvmStatic
    fun describe(provider: String, error: Exception): Exception = OpenDriveFailurePolicy.describe(provider, error, STATUSES)
}
