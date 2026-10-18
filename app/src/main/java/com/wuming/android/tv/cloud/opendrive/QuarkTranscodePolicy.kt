package com.wuming.android.tv.cloud.opendrive

import com.wuming.tv.core.cloud.OpenDriveFailure
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.IOException

internal object QuarkTranscodePolicy {

    @JvmStatic
    @Throws(IOException::class)
    fun selectTranscode(videos: JsonArray, requestedResolution: String): JsonObject {
        return try {
            com.wuming.tv.core.cloud.QuarkTranscodePolicy.selectTranscode(videos, requestedResolution)
        } catch (failure: OpenDriveFailure) {
            if (failure.provider() == "quark" &&
                failure.reason() == OpenDriveFailure.Reason.TRANSCODE_UNAVAILABLE
            ) {
                throw QuarkSmartQualityException()
            }
            throw failure
        }
    }

    @JvmStatic
    fun transcodeResolution(flag: String?): String =
        com.wuming.tv.core.cloud.QuarkTranscodePolicy.transcodeResolution(flag)
}
