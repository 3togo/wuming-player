package com.wuming.android.tv.cloud.opendrive

import java.io.InputStream
import java.util.Collections
import java.util.LinkedHashMap

class OpenDriveProxyResponse(
    status: Int,
    mimeType: String?,
    stream: InputStream,
    headers: Map<String, String>?
) {
    val status: Int = status
    val mimeType: String = if (mimeType.isNullOrBlank()) "application/octet-stream" else mimeType
    val stream: InputStream = stream
    val headers: Map<String, String> = headers?.let { Collections.unmodifiableMap(LinkedHashMap(it)) } ?: emptyMap()

    init {
        require(status in 100..599) { "Invalid proxy status" }
        requireNotNull(stream) { "Proxy stream is required" }
    }

    fun status(): Int = status
    fun mimeType(): String = mimeType
    fun stream(): InputStream = stream
    fun headers(): Map<String, String> = headers

    override fun equals(other: Any?): Boolean =
        other is OpenDriveProxyResponse && status == other.status &&
        mimeType == other.mimeType &&
        stream == other.stream &&
        headers == other.headers

    override fun hashCode(): Int = listOf(status, mimeType, stream, headers).hashCode()

    override fun toString(): String = "OpenDriveProxyResponse(status=$status, mimeType=$mimeType, stream=$stream, headers=$headers)"


    fun toCatVodResponse(): Array<Any?> =
        if (headers.isEmpty()) arrayOf(status, mimeType, stream)
        else arrayOf(status, mimeType, stream, headers)

    companion object {
        @JvmStatic
        fun mediaHeaders(contentRange: String?, contentLength: Long): Map<String, String> {
            if (contentRange.isNullOrBlank() && contentLength < 0L) return emptyMap()
            val result = LinkedHashMap<String, String>()
            if (!contentRange.isNullOrBlank()) {
                result["Content-Range"] = contentRange
                result["Accept-Ranges"] = "bytes"
            }
            if (contentLength >= 0L) result["Content-Length"] = contentLength.toString()
            return result
        }
    }
}
