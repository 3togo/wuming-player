package com.wuming.android.tv.player.exo

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import com.wuming.android.tv.R
import com.wuming.android.tv.utils.ResUtil

class ErrorMsgProvider {

    fun get(e: PlaybackException): String {
        UnsupportedVideoException.find(e)?.let {
            return ResUtil.getString(it.messageResource()) + "\n" + it.details()
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_TIMEOUT -> "Timeout"
            PlaybackException.ERROR_CODE_UNSPECIFIED -> "Unspecified"
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> "Failed Runtime Check"
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "IO Unspecified"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> getHttpErrorMessage(e)
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "Invalid HTTP Content Type"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> ResUtil.getString(R.string.error_play_connection)
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> "Read Position Out Of Range"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Manifest Malformed"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Container Malformed"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "Manifest Unsupported"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Container Unsupported"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Decoder Init Failed"
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "Decoder Query Failed"
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "Decoding Failed"
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> getUnsupportedVideoMessage(e)
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> "Decoding Resources Reclaimed"
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> getUnsupportedVideoMessage(e)
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "Audio Track Init Failed"
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "Audio Track Write Failed"
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> "DRM Unspecified"
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR -> "DRM System Error"
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR -> "DRM Content Error"
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED -> "DRM Device Revoked"
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> "DRM License Expired"
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED -> "DRM Provisioning Failed"
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION -> "DRM Disallowed Operation"
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED -> "DRM License Acquisition Failed"
            else -> e.errorCodeName
        }
    }

    private fun getUnsupportedVideoMessage(error: PlaybackException): String {
        val message = ResUtil.getString(R.string.error_play_video_unsupported)
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is UnsupportedVideoException) {
                return message + "\n" + cause.details()
            }
            cause = cause.cause
        }
        if (error is ExoPlaybackException) {
            val format = error.rendererFormat
            if (format != null) {
                return message + "\n" + UnsupportedVideoException(format, error.rendererFormatSupport).details()
            }
        }
        return message
    }

    private fun getHttpErrorMessage(e: PlaybackException): String {
        val providerMessage = getProviderHttpError(e)
        if (providerMessage.isNotBlank()) return providerMessage
        HttpPlaybackErrors.responseCode(e)?.let { return getDetailedHttpMessage(it) }

        val message = e.message
        Log.d(TAG, "HTTP Error - Message: $message")
        Log.d(TAG, "HTTP Error - Cause: ${e.cause?.message ?: "null"}")

        if (message != null && message.contains("Response code:")) {
            try {
                val statusCode = extractStatusCode(message)
                Log.d(TAG, "Extracted status code: $statusCode")
                return getDetailedHttpMessage(statusCode)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to parse status code", ex)
            }
        }

        val causeMessage = e.cause?.message
        Log.d(TAG, "Cause message: $causeMessage")
        if (causeMessage != null && causeMessage.contains("Response code:")) {
            try {
                val statusCode = extractStatusCode(causeMessage)
                Log.d(TAG, "Extracted status code from cause: $statusCode")
                return getDetailedHttpMessage(statusCode)
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to parse status code from cause", ex)
            }
        }

        return "Bad HTTP Status"
    }

    private fun extractStatusCode(message: String): Int {
        val codeStart = message.indexOf("Response code:") + 14
        var codeEnd = message.indexOf(",", codeStart)
        if (codeEnd == -1) codeEnd = message.indexOf("]", codeStart)
        if (codeEnd == -1) codeEnd = message.length
        return message.substring(codeStart, codeEnd).trim().toInt()
    }

    private fun getProviderHttpError(error: PlaybackException): String {
        var current: Throwable? = error
        while (current != null) {
            if (current is HttpDataSource.InvalidResponseCodeException) {
                val message = providerMessage(current.headerFields)
                if (message.isNotBlank()) {
                    return "HTTP " + current.responseCode + " " +
                        getHttpStatusDescription(current.responseCode) + "\n" + message
                }
            }
            current = current.cause
        }
        return ""
    }

    companion object {
        private const val TAG = "ErrorMsgProvider"

        @JvmStatic
        fun providerMessage(headers: Map<String, List<String>>?): String {
            if (headers == null) return ""
            for ((key, values) in headers) {
                if (!"X-OpenDrive-Error".equals(key, ignoreCase = true)) continue
                if (values.isEmpty()) return ""
                val value = values[0] as String?
                if (value == null || value.isBlank() || value.length > 256 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) return ""
                return value
            }
            return ""
        }
    }

    private fun getDetailedHttpMessage(statusCode: Int): String {
        val description = getHttpStatusDescription(statusCode)
        val suggestion = getHttpSuggestion(statusCode)
        val result = if (suggestion.isNotEmpty()) {
            "HTTP $statusCode $description\n$suggestion"
        } else {
            "HTTP $statusCode $description"
        }
        Log.d(TAG, "Detailed message for $statusCode: $result")
        return result
    }

    private fun getHttpSuggestion(statusCode: Int): String = when (statusCode) {
        401 -> "Authentication required. Check if login is needed."
        403 -> ResUtil.getString(R.string.error_play_access_denied)
        404 -> "Resource not found. The URL may be invalid or expired."
        429 -> "Too many requests. Wait a moment and try again."
        500, 502, 503, 504 -> "Server error. Try again later or use a different source."
        else -> ""
    }

    private fun getHttpStatusDescription(statusCode: Int): String = when (statusCode) {
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> ""
    }
}
