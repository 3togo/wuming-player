package com.wuming.android.tv.api

import androidx.collection.ArrayMap
import com.wuming.android.tv.App
import com.wuming.android.tv.bean.BeanUtils
import com.wuming.android.tv.bean.Danmaku
import com.wuming.android.tv.cloud.opendrive.UserCredentialStore
import com.wuming.android.tv.impl.Callback
import com.wuming.android.tv.setting.DanmakuSetting
import com.wuming.tv.core.cloud.UserCredentialMethod
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Trans
import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.function.Consumer

open class DanmakuApi {
    companion object {
        private val TAG = DanmakuApi::class.java.simpleName

        @JvmStatic fun canSearch(): Boolean =
            DanmakuSetting.isLoad() && DanmakuSetting.isAuto() && BeanUtils.isNotEmpty(DanmakuSetting.getEffectiveApiUrl())

        @JvmStatic fun newCall(
            name: String,
            episode: String,
        ): Call {
            cancel()
            return createCall(name, episode, TAG)
        }

        /** Caller owns this call and cancels it without affecting another search. */
        @JvmStatic fun createCall(
            name: String,
            episode: String,
        ): Call = createCall(name, episode, null)

        private fun createCall(
            name: String,
            episode: String,
            tag: String?,
        ): Call {
            val convertedName = Trans.t2s(name)!!
            val convertedEpisode = Trans.t2s(episode)!!
            val url = DanmakuSetting.getEffectiveApiUrl()!!
            val headers = authHeaders(url)
            if (url.contains("{name}") ||
                url.contains("{episode}")
            ) {
                return OkHttp.newCall(
                    url.replace("{name}", convertedName).replace("{episode}", convertedEpisode),
                    headers,
                    tag,
                )
            }
            val params = ArrayMap<String, String>()
            params["name"] = convertedName
            params["episode"] = convertedEpisode
            return OkHttp.newCall(url, headers, OkHttp.toBody(params), tag)
        }

        /**
         * Attaches a user-supplied credential from the OpenDrive vault when one's
         * configured host matches the danmaku endpoint. This is explicit credential
         * replay (the user saved the cookie/token), not an in-app login flow, so it
         * needs no per-service protocol evidence. Returns an empty map when none match.
         */
        private fun authHeaders(url: String): ArrayMap<String, String> {
            val headers = ArrayMap<String, String>()
            val credential = UserCredentialStore.findByUrl(url) ?: return headers
            when (credential.method) {
                UserCredentialMethod.COOKIE -> {
                    val cookie = UserCredentialStore.getCookie(credential.serviceId)
                    if (cookie.isNotEmpty()) headers["Cookie"] = cookie
                }
                UserCredentialMethod.OAUTH_TOKEN -> {
                    val token = UserCredentialStore.getToken(credential.serviceId)
                    if (token.isNotEmpty()) headers["Authorization"] = "Bearer $token"
                }
                UserCredentialMethod.PASSWORD -> { /* not replayable as a request header */ }
            }
            return headers
        }

        @JvmStatic fun search(
            name: String,
            episode: String,
            found: Consumer<Danmaku>?,
        ) {
            newCall(name, episode).enqueue(SearchCallback(found))
        }

        @JvmStatic fun cancel() {
            OkHttp.cancel(TAG)
        }
    }

    private class SearchCallback(
        found: Consumer<Danmaku>?,
    ) : Callback() {
        private val foundRef = WeakReference(found)

        @Throws(IOException::class)
        override fun onResponse(
            call: Call,
            response: Response,
        ) {
            try {
                response.use {
                    val body = response.body
                    if (foundRef.get() != null) {
                        val item = Danmaku.arrayFrom(body.string()).firstOrNull()
                        if (item != null) App.post { foundRef.get()?.accept(item) }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
