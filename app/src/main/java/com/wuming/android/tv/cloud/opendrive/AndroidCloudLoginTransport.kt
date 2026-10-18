package com.wuming.android.tv.cloud.opendrive

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.android.tv.cloud.opendrive.OpenDriveHttp.JsonResponse
import com.wuming.android.tv.cloud.opendrive.OpenDriveHttp.TextResponse
import com.wuming.tv.core.runtime.cloudlogin.CloudLoginResponse
import com.wuming.tv.core.runtime.cloudlogin.CloudLoginTextResponse
import com.wuming.tv.core.runtime.cloudlogin.CloudLoginTimeoutException
import com.wuming.tv.core.runtime.cloudlogin.CloudLoginTransport
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * Adapts Android's [OpenDriveHttp] (OkHttp) onto the platform-neutral
 * [CloudLoginTransport] used by the shared Kotlin login providers. The
 * explicit-cookie client is used so the providers own their cookie state instead
 * of Android's shared jar replacing it.
 *
 *
 * [CloudLoginTransport] does not declare checked exceptions, so every
 * checked exception from [OpenDriveHttp] is translated: socket timeouts
 * become [CloudLoginTimeoutException] (so Baidu's "keep polling" handling
 * still triggers), and any other failure becomes an unchecked [RuntimeException].
 */
class AndroidCloudLoginTransport : CloudLoginTransport {
    override fun get(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>
    ): CloudLoginResponse {
        try {
            val response: JsonResponse = OpenDriveHttp.getExplicit(url, query, headers)
            return CloudLoginResponse(response.body(), response.setCookieHeaders())
        } catch (error: Exception) {
            throw translate(error)
        }
    }

    override fun getText(
        url: String,
        query: Map<String, String>,
        headers: Map<String, String>
    ): CloudLoginTextResponse {
        try {
            val response: TextResponse = OpenDriveHttp.getTextExplicit(url, query, headers)
            return CloudLoginTextResponse(response.body(), response.setCookieHeaders())
        } catch (error: Exception) {
            throw translate(error)
        }
    }

    override fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String>
    ): CloudLoginResponse {
        try {
            val response: JsonResponse = OpenDriveHttp.postFormExplicit(url, form, headers)
            return CloudLoginResponse(response.body(), response.setCookieHeaders())
        } catch (error: Exception) {
            throw translate(error)
        }
    }

    override fun postJson(
        url: String,
        json: String,
        headers: Map<String, String>
    ): CloudLoginResponse {
        try {
            val body: JsonObject = JsonParser.parseString(json).getAsJsonObject()
            val response: JsonResponse = OpenDriveHttp.postJsonExplicit(url, body, headers)
            return CloudLoginResponse(response.body(), response.setCookieHeaders())
        } catch (error: Exception) {
            throw translate(error)
        }
    }

    companion object {
        @JvmStatic
        private fun translate(error: Exception): RuntimeException {
            if (error is SocketTimeoutException || error is InterruptedIOException) {
                return CloudLoginTimeoutException(error.message, error)
            }
            return RuntimeException(error)
        }
    }
}
