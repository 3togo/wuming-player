package com.github.catvod.net.interceptor

import androidx.annotation.NonNull
import androidx.annotation.Nullable
import com.github.catvod.bean.Header
import com.github.catvod.utils.Json
import com.github.catvod.utils.Json.toMap
import com.github.catvod.utils.Util
import com.google.common.net.HttpHeaders
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import okhttp3.Interceptor
import okhttp3.Interceptor.Chain
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.Okio
import okio.buffer
import okio.source

open class ResponseInterceptor constructor() : Interceptor {
    private val headers: MutableList<Header>
    private val redirectMap: ConcurrentHashMap<String?, String?>

    init {
        headers = CopyOnWriteArrayList<Header>()
        redirectMap = ConcurrentHashMap<String?, String?>()
    }

    open fun addAll(items: MutableList<Header>) {
        headers.addAll(items)
    }

    open fun clear() {
        headers.clear()
        redirectMap.clear()
    }

    @Throws(IOException::class)
    override fun intercept(chain: Chain): Response {
        val request: Request = check(chain.request())
        val response: Response = chain.proceed(request)
        if ("deflate" == response.header(HttpHeaders.CONTENT_ENCODING)) return deflate(response)
        if (response.code == 406 &&
            redirectMap.containsKey(request.url.toString())
        ) {
            return redirect(
                request,
                response
            )
        }
        if (response.code == 302 && response.header(HttpHeaders.LOCATION) != null) {
            if (redirectMap.size >= MAX_REDIRECT_MAP_SIZE) redirectMap.clear()
            redirectMap.put(response.header(HttpHeaders.LOCATION), request.url.toString())
        }
        return response
    }

    private fun check(request: Request): Request {
        val host: String = request.url.host
        val builder: Request.Builder = request.newBuilder()
        for (item: Header in headers) {
            if (Util.containOrMatch(
                    host,
                    item.getHost()
                )
            ) {
                toMap(item.getHeader()!!).forEach { (name: String?, value: String?) ->
                    builder.header(
                        name!!,
                        value!!
                    )
                }
            }
        }
        return builder.build()
    }

    private fun redirect(request: Request, response: Response): Response =
        Response.Builder().request(request).protocol(response.protocol).code(302)
            .message("Found").header(
                HttpHeaders.LOCATION,
                redirectMap.get(request.url.toString())!!
            ).build()

    private fun deflate(response: Response): Response {
        val originalBody: ResponseBody = response.body
        val contentType: MediaType? = originalBody.contentType()
        val contentLength: Long = originalBody.contentLength()
        val `is`: InflaterInputStream =
            InflaterInputStream(originalBody.byteStream(), Inflater(true))
        return response.newBuilder().headers(response.headers).body(object : ResponseBody() {
            override fun contentType(): MediaType? = contentType

            override fun contentLength(): Long = contentLength

            override fun source(): BufferedSource = `is`.source().buffer()

            override fun close() {
                super.close()
                try {
                    `is`.close()
                } catch (e: IOException) {
                    // Ignore
                }
            }
        }).build()
    }

    companion object {
        private const val MAX_REDIRECT_MAP_SIZE = 50
    }
}
