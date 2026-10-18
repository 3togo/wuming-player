package com.wuming.android.tv.cloud.opendrive

import android.util.Log
import com.google.gson.JsonObject
import com.wuming.tv.core.cloud.OpenDriveFailure
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Response

/** Metadata only: never emit request URLs, headers, bodies, or exception messages. */
internal object OpenDriveDiagnostics {
    @JvmStatic
    fun factory(): EventListener.Factory = factory(Consumer(::log))

    @JvmStatic
    fun factory(sink: Consumer<String>?): EventListener.Factory =
        EventListener.Factory { call ->
            object : EventListener() {
                private val started = System.nanoTime()
                private val provider = provider(call.request().url.host)

                private fun emit(phase: String, status: Int) {
                    // Diagnostics must never interrupt a request, even if the sink fails.
                    try {
                        sink?.accept(
                            "provider=$provider phase=$phase http=$status elapsedMs=" +
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                        )
                    } catch (_: RuntimeException) {
                    }
                }

                override fun callStart(call: Call) = emit("start", 0)

                override fun dnsStart(call: Call, domainName: String) = emit("dns", 0)

                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) =
                    emit("connect", 0)

                override fun responseHeadersEnd(call: Call, response: Response) = emit("headers", response.code)

                override fun callEnd(call: Call) = emit("complete", 0)

                override fun callFailed(call: Call, ioe: IOException) = emit("failed", 0)
            }
        }

    @JvmStatic
    fun provider(host: String): String =
        when (host) {
            "drive-pc.quark.cn", "drive.quark.cn" -> "quark"
            "pc-api.uc.cn", "drive.uc.cn" -> "uc"
            "api.aliyundrive.com" -> "alipan"
            "pan.baidu.com" -> "baidu"
            else -> "other" // Signed CDN hosts can contain user-controlled identifiers.
        }

    @JvmStatic
    fun apiSummary(host: String, body: JsonObject?): String {
        var code = OpenDriveHttp.string(body, "code")
        if (code.isEmpty()) code = OpenDriveHttp.string(body, "errno")
        // Numeric codes only. Even a syntactically valid string may be a credential.
        if (!code.matches(Regex("-?[0-9]{1,10}"))) code = if (code.isEmpty()) "absent" else "non_numeric"
        return "provider=${provider(host)} phase=api code=$code"
    }

    @JvmStatic
    fun api(host: String, body: JsonObject?) = log(apiSummary(host, body))

    @JvmStatic
    fun failure(failure: OpenDriveFailure) {
        log(
            "provider=${failure.provider()} phase=resolve_failure reason=${failure.reason().name}" +
                " http=${failure.httpStatus()} api=${failure.apiCode()}"
        )
    }

    private fun log(message: String) {
        try {
            Log.d("OpenDriveDiagnostics", message)
        } catch (_: RuntimeException) {
        }
    }
}
