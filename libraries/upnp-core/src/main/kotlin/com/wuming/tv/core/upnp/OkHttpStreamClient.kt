package com.wuming.tv.core.upnp

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import org.fourthline.cling.model.message.StreamRequestMessage
import org.fourthline.cling.model.message.StreamResponseMessage
import org.fourthline.cling.model.message.UpnpHeaders
import org.fourthline.cling.model.message.UpnpResponse
import org.fourthline.cling.transport.spi.AbstractStreamClient
import org.fourthline.cling.transport.spi.AbstractStreamClientConfiguration
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * jUPnP [AbstractStreamClient] backed by OkHttp, shared by the Android and desktop UPnP stacks.
 * The two ports differ only in transport policy, passed through the constructor so neither port's
 * behaviour leaks into the other:
 *  - [bypassProxy]: desktop bypasses the JVM/system proxy so multicast discovery talks to the LAN
 *    directly; Android keeps system proxy selection.
 *  - [maxBodyBytes]: desktop caps responses at 1 MiB; Android inherits the full body (pre-existing
 *    behaviour). The [writeTimeoutSeconds] belongs to the same desktop hardening.
 * Copying one port's policy onto the other would change behaviour.
 */
open class OkHttpStreamClient @JvmOverloads constructor(
    private val configuration: Configuration,
    private val bypassProxy: Boolean = false,
    private val maxBodyBytes: Int = Int.MAX_VALUE,
) : AbstractStreamClient<OkHttpStreamClient.Configuration, Call>() {

    private val httpClient: OkHttpClient

    init {
        val timeout = configuration.timeoutSeconds + 5
        httpClient = OkHttpClient.Builder()
            .connectTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeout.toLong(), TimeUnit.SECONDS)
            .apply { if (bypassProxy) proxy(Proxy.NO_PROXY) }
            .build()
    }

    override fun getConfiguration(): Configuration = configuration

    public override fun createRequest(requestMessage: StreamRequestMessage): Call {
        val method = requestMessage.operation.httpMethodName
        val builder = Request.Builder()
            .url(requestMessage.operation.uri.toString())
            .method(method, buildRequestBody(requestMessage, method))
        requestMessage.headers.forEach { (key, values) ->
            values.filterNotNull().forEach { builder.addHeader(key, it) }
        }
        // Some renderers silently expire idle HTTP connections: a later Stop then
        // receives no response on the pooled socket. Control messages are small;
        // give each request a fresh connection instead of depending on that pool.
        builder.header("Connection", "close")
        if (requestMessage.headers["user-agent"] == null) {
            builder.header(
                "User-Agent",
                configuration.getUserAgentValue(requestMessage.udaMajorVersion, requestMessage.udaMinorVersion),
            )
        }
        return httpClient.newCall(builder.build())
    }

    private fun buildRequestBody(requestMessage: StreamRequestMessage, method: String): RequestBody? {
        if (requestMessage.hasBody()) {
            val bytes = requestMessage.bodyBytes
            if (bytes != null && bytes.isNotEmpty()) {
                val ct = requestMessage.headers["content-type"]
                val mediaType = if (ct != null && ct.isNotEmpty()) ct[0].toMediaTypeOrNull() else null
                return bytes.toRequestBody(mediaType)
            }
        }
        return if (requiresBody(method)) byteArrayOf().toRequestBody() else null
    }

    public override fun createCallable(
        requestMessage: StreamRequestMessage,
        call: Call,
    ): Callable<StreamResponseMessage> = Callable {
        call.execute().use { response ->
            val message = StreamResponseMessage(UpnpResponse(response.code, response.message))
            val headers = UpnpHeaders()
            response.headers.names().forEach { name ->
                response.headers(name).forEach { headers.add(name, it) }
            }
            message.headers = headers
            val bytes = readBody(response)
            if (bytes.isNotEmpty()) message.setBodyCharacters(bytes)
            message
        }
    }

    private fun readBody(response: Response): ByteArray {
        val body: ResponseBody = response.body ?: return byteArrayOf()
        if (maxBodyBytes == Int.MAX_VALUE) {
            return body.source().readByteArray()
        }
        val stream = body.byteStream()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            out.write(buffer, 0, read)
            total += read
            if (total > maxBodyBytes) {
                throw IOException("UPnP response too large: $total bytes (limit $maxBodyBytes)")
            }
        }
        return out.toByteArray()
    }

    public override fun abort(call: Call) {
        call.cancel()
    }

    public override fun logExecutionException(t: Throwable?): Boolean = false

    override fun stop() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    open class Configuration(
        executorService: ExecutorService,
    ) : AbstractStreamClientConfiguration(executorService) {
        override fun getUserAgentValue(majorVersion: Int, minorVersion: Int): String =
            "Dalvik/${System.getProperty("java.vm.version")} UMS/1.0"
    }

    private companion object {
        private fun requiresBody(method: String): Boolean =
            method == "POST" || method == "NOTIFY" || method == "PUT"
    }
}
