package com.wuming.chaquo

import android.content.Context
import com.chaquo.python.PyObject
import com.github.catvod.utils.Path
import com.github.catvod.utils.UriUtil
import com.github.catvod.utils.Util
import com.google.gson.Gson
import java.io.ByteArrayInputStream
import java.nio.charset.Charset

open class Spider(private val app: PyObject, private val obj: PyObject, private val api: String?) :
    com.github.catvod.crawler.Spider() {
    private val gson = Gson()

    override fun init(context: Context?, extend: String?) {
        val dependence = app.callAttr("getDependence", obj)
        if (dependence != null) for (item in dependence.asList()) download("$item.py")
        obj.put("siteKey", siteKey)
        app.callAttr("init", obj, extend)
    }

    override fun homeContent(filter: Boolean): String =
        app.callAttr("homeContent", obj, filter).toString()
    override fun homeVideoContent(): String = app.callAttr("homeVideoContent", obj).toString()
    override fun categoryContent(
        tid: String?,
        pg: String?,
        filter: Boolean,
        extend: HashMap<String, String>?
    ): String =
        app.callAttr("categoryContent", obj, tid, pg, filter, gson.toJson(extend)).toString()
    override fun detailContent(ids: MutableList<String>?): String =
        app.callAttr("detailContent", obj, gson.toJson(ids)).toString()
    override fun searchContent(key: String?, quick: Boolean): String =
        app.callAttr("searchContent", obj, key, quick).toString()
    override fun searchContent(key: String?, quick: Boolean, pg: String?): String =
        app.callAttr("searchContent", obj, key, quick, pg).toString()
    override fun playerContent(flag: String?, id: String?, vipFlags: MutableList<String>?): String =
        app.callAttr("playerContent", obj, flag, id, gson.toJson(vipFlags)).toString()
    override fun liveContent(url: String?): String =
        app.callAttr("liveContent", obj, url).toString()
    override fun manualVideoCheck(): Boolean = app.callAttr("manualVideoCheck", obj).toBoolean()
    override fun isVideoFormat(url: String?): Boolean =
        app.callAttr("isVideoFormat", obj, url).toBoolean()

    @Throws(Exception::class)
    override fun proxy(params: MutableMap<String, String>?): Array<Any?> {
        val list = app.callAttr("localProxy", obj, gson.toJson(params)).asList()
        val base64 = list.size > 4 && list[4].toInt() == 1
        val header = list.size > 3 && list[3] != null
        return arrayOf(
            list[0].toInt(),
            list[1].toString(),
            getStream(list[2], base64),
            if (header) getHeader(list[3]) else null
        )
    }

    override fun action(action: String?): String = app.callAttr("action", obj, action).toString()
    override fun destroy() {
        try {
            app.callAttr("destroy", obj)
        } catch (_: Exception) { }
    }

    private fun getHeader(value: PyObject?): Map<String, String>? = try {
        val header = HashMap<String, String>()
        for ((key, item) in value!!.asMap()) header[key.toString()] = item.toString()
        header
    } catch (_: Exception) {
        null
    }

    private fun getStream(value: PyObject?, base64: Boolean): ByteArrayInputStream? {
        if (value == null) return null
        if (value.type().toString().contains(
                "bytes"
            )
        ) {
            return ByteArrayInputStream(value.toJava(ByteArray::class.java))
        }
        var content = value.toString()
        if (base64 &&
            content.contains("base64,")
        ) {
            content =
                java.util.regex.Pattern.compile("base64,").split(content)[1]
        }
        return ByteArrayInputStream(
            if (base64) Util.decode(content) else content.toByteArray(Charset.defaultCharset())
        )
    }

    private fun download(name: String) {
        val path = Path.py(name).absolutePath
        app.callAttr("download", path, UriUtil.resolve(api, name))
    }
}
