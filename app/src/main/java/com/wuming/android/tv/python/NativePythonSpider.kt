package com.wuming.android.tv.python

import android.content.Context
import com.github.catvod.Proxy
import com.github.catvod.net.OkHttp
import com.wuming.android.tv.App
import com.wuming.tv.core.runtime.python.PythonProvider
import com.wuming.tv.core.runtime.python.PythonSourceSpec
import com.wuming.tv.core.source.packages.PackageSourceIdentity
import java.io.File
import java.util.Base64

class NativePythonSpider(private val key: String, private val api: String) : com.github.catvod.crawler.Spider() {
    private var provider: PythonProvider? = null
    override fun init(context: Context?, extend: String?) {
        provider = PythonProvider(
            PythonSourceSpec(key, key, api, extend.orEmpty(), PackageSourceIdentity.original(key)),
            File(App.get().filesDir, "python-providers"), AndroidPythonRuntime::start,
            { url ->
                OkHttp.newCall(url).execute().use { response ->
                    check(response.isSuccessful) { "Python source HTTP ${response.code}" }
                    response.body.byteStream().use { stream -> readAtMost(stream, 4 * 1024 * 1024) }
                }
            }, mapOf("proxyUrl" to (Proxy.getUrl(true) + "?do=py&siteKey=" + java.net.URLEncoder.encode(key, "UTF-8"))),
        )
    }

    /** `InputStream.readNBytes` is API 33; this caps the same way without the level bump. */
    private fun readAtMost(stream: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16384)
        while (out.size() <= limit) {
            val count = stream.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
        }
        require(out.size() <= limit) { "Python source exceeds 4 MiB" }
        return out.toByteArray()
    }
    private fun call(method: String, vararg args: Any?) = checkNotNull(provider) { "Python spider is not initialized" }.call(method, *args)
    override fun homeContent(filter: Boolean) = call("homeContent", filter).toString()
    override fun homeVideoContent() = call("homeVideoContent").toString()
    override fun categoryContent(tid: String?, pg: String?, filter: Boolean, extend: HashMap<String, String>?) = call("categoryContent", tid, pg, filter, extend.orEmpty()).toString()
    override fun detailContent(ids: MutableList<String>?) = call("detailContent", ids.orEmpty()).toString()
    override fun searchContent(key: String?, quick: Boolean) = searchContent(key, quick, "1")
    override fun searchContent(key: String?, quick: Boolean, pg: String?) = call("searchContent", key, quick, pg).toString()
    override fun playerContent(flag: String?, id: String?, vipFlags: MutableList<String>?) = call("playerContent", flag, id, vipFlags.orEmpty()).toString()
    override fun liveContent(url: String?) = call("liveContent", url).let { if (it.isJsonNull) "" else if (it.isJsonPrimitive) it.asString else it.toString() }
    override fun manualVideoCheck() = call("manualVideoCheck").let { !it.isJsonNull && it.asBoolean }
    override fun isVideoFormat(url: String?) = call("isVideoFormat", url).let { !it.isJsonNull && it.asBoolean }
    override fun action(action: String?) = call("action", action).toString()
    override fun proxy(params: MutableMap<String, String>?): Array<Any?> {
        val value = call("localProxy", params.orEmpty()).asJsonObject
        val headers = value.getAsJsonObject("headers").entrySet().associate { it.key to it.value.asString }
        return arrayOf(value.get("status").asInt, value.get("contentType").asString,
            Base64.getDecoder().decode(value.get("body").asString).inputStream(), headers)
    }
    override fun destroy() { val previous = provider; provider = null; previous?.close() }
}
