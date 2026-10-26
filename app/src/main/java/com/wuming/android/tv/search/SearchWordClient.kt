package com.wuming.android.tv.search

import com.wuming.android.tv.App
import com.wuming.android.tv.impl.Callback
import com.github.catvod.net.OkHttp
import com.google.common.net.HttpHeaders
import com.wuming.tv.core.search.SearchSuggestions
import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.function.Consumer

class SearchWordClient {
    private val calls: MutableMap<Call, ResultCallback> = LinkedHashMap()
    private val hot360Url: String
    private val hotTencentUrl: String
    private val suggestUrl: String
    private var generation = 0

    constructor() : this(HOT_360, HOT_TENCENT, SUGGEST)

    constructor(hot360Url: String, hotTencentUrl: String, suggestUrl: String) {
        this.hot360Url = hot360Url
        this.hotTencentUrl = hotTencentUrl
        this.suggestUrl = suggestUrl
    }

    @Synchronized
    fun requestHot(primary: ResultCallback, secondary: ResultCallback?) {
        val requestGeneration = beginRequest()
        enqueue(hot360Url, mapOf(HttpHeaders.REFERER to "https://www.360kan.com/rank/general"), requestGeneration, primary)
        if (secondary != null) enqueue(hotTencentUrl + System.currentTimeMillis(), emptyMap(), requestGeneration, secondary)
    }

    @Synchronized
    fun requestSuggestion(keyword: String, callback: ResultCallback) {
        val requestGeneration = beginRequest()
        enqueue(suggestUrl + URLEncoder.encode(keyword, StandardCharsets.UTF_8), emptyMap(), requestGeneration, callback)
    }

    @Synchronized
    fun requestSuggestionWords(keyword: String, callback: Consumer<List<String>>) {
        val requestGeneration = beginRequest()
        callback.accept(SearchSuggestions.local(keyword))
        if (keyword.isBlank() || generation != requestGeneration) return
        enqueue(suggestUrl + URLEncoder.encode(keyword, StandardCharsets.UTF_8), emptyMap(), requestGeneration) { result ->
            callback.accept(SearchSuggestions.merge(keyword, SearchSuggestions.parseResponse(result)))
        }
    }

    @Synchronized
    fun cancel() {
        generation++
        for (call in calls.keys) call.cancel()
        calls.clear()
    }

    private fun beginRequest(): Int {
        cancel()
        return generation
    }

    private fun enqueue(url: String, headers: Map<String, String>, requestGeneration: Int, callback: ResultCallback) {
        val call = OkHttp.newCall(url, headers)
        calls[call] = callback
        call.enqueue(EnqueueCallback(this, requestGeneration))
    }

    @Synchronized
    private fun onCallFinished(call: Call) {
        calls.remove(call)
    }

    @Synchronized
    private fun deliver(call: Call, requestGeneration: Int, result: String) {
        val callback = calls.remove(call)
        if (callback != null && generation == requestGeneration && result.isNotEmpty()) {
            callback.onResult(result)
        }
    }

    @Synchronized
    fun inFlightCount(): Int = calls.size

    private class EnqueueCallback(
        client: SearchWordClient,
        private val requestGeneration: Int,
    ) : Callback() {
        private val clientRef = WeakReference(client)

        override fun onFailure(call: Call, e: IOException) {
            clientRef.get()?.onCallFinished(call)
        }

        override fun onResponse(call: Call, response: Response) {
            val result: String
            try {
                response.use { resp ->
                    result = resp.body.string()
                }
            } catch (error: IOException) {
                clientRef.get()?.onCallFinished(call)
                throw error
            } catch (error: RuntimeException) {
                clientRef.get()?.onCallFinished(call)
                throw error
            }
            // The queued delivery owns no UI callback. Cancellation removes it
            // from the client's map immediately, even if the main queue is busy.
            App.post {
                clientRef.get()?.deliver(call, requestGeneration, result)
            }
        }
    }

    fun interface ResultCallback {
        fun onResult(result: String)
    }

    companion object {
        private const val HOT_360 = "https://api.web.360kan.com/v1/rank?cat=1"
        private const val HOT_TENCENT = "https://node.video.qq.com/x/api/hot_search?channdlId=0&_="
        private const val SUGGEST = "https://suggest.video.iqiyi.com/?if=mobile&key="
    }
}
