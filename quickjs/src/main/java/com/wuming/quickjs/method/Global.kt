package com.wuming.quickjs.method

import androidx.annotation.Keep
import androidx.annotation.NonNull
import com.github.catvod.Proxy
import com.github.catvod.Proxy.getUrl
import com.github.catvod.utils.Trans
import com.github.catvod.utils.Trans.s2t
import com.github.catvod.utils.Trans.t2s
import com.github.catvod.utils.UriUtil
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSMethod
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import com.wuming.quickjs.bean.Req
import com.wuming.quickjs.bean.Req.Companion.objectFrom
import com.wuming.quickjs.utils.Connect
import com.wuming.quickjs.utils.Crypto
import com.wuming.quickjs.utils.Crypto.aes
import com.wuming.quickjs.utils.Crypto.md5
import com.wuming.quickjs.utils.Crypto.rsa
import java.io.IOException
import java.lang.reflect.Method
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.HashSet
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.Volatile
import kotlin.math.max
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

open class Global constructor(ctx: QuickJSContext?, private val executor: ExecutorService) {
    private val ctx: QuickJSContext?
    private val timer: Timer

    // Native handles are only touched on the QuickJS executor thread.
    private val functions: MutableMap<Long?, JSFunction> = HashMap<Long?, JSFunction>()
    private val calls: MutableSet<Call> = HashSet<Call>()
    private val responses: MutableSet<Response> = HashSet<Response>()
    private var nextFunctionId: Long = 0

    @Volatile
    private var closed = false

    init {
        this.timer = Timer("QuickJS-timers", true)
        this.ctx = ctx
    }

    private fun setProperty() {
        for (method: Method in javaClass.getMethods()) {
            if (!method.isAnnotationPresent(JSMethod::class.java)) continue
            ctx!!.getGlobalObject()
                .setProperty(
                    method.getName(),
                    JSCallFunction { args: Array<Any?>? ->
                        try {
                            return@JSCallFunction method.invoke(this, *(args ?: emptyArray()))
                        } catch (e: Exception) {
                            return@JSCallFunction null
                        } finally {
                            // The bridge gives Java an owned wrapper for each object
                            // argument. Async methods explicitly hold what they retain.
                            if (args != null) {
                                for (arg: Any? in args) if (arg is JSObject) arg.release()
                            }
                        }
                    }
                )
        }
    }

    @Synchronized
    private fun submit(runnable: Runnable): Boolean {
        if (closed) return false
        try {
            executor.execute(
                Runnable {
                    if (!closed) runnable.run()
                }
            )
            return true
        } catch (ignored: RejectedExecutionException) {
            return false
        }
    }

    @Keep
    @JSMethod
    open fun s2t(text: String): String = s2t(false, text)

    @Keep
    @JSMethod
    open fun t2s(text: String): String = t2s(false, text)

    @get:JSMethod
    @get:Keep
    open val port: Int?
        get() = Proxy.getPort()

    @Keep
    @JSMethod
    open fun getProxy(local: Boolean?): String = getUrl(local!!) + "?do=js"

    @Keep
    @JSMethod
    open fun js2Proxy(
        dynamic: Boolean?,
        siteType: Int?,
        siteKey: String?,
        url: String?,
        headers: JSObject
    ): String = getProxy(!dynamic!!) + String.format(
        "&from=catvod&siteType=%s&siteKey=%s&header=%s&url=%s",
        siteType,
        siteKey,
        URLEncoder.encode(headers.stringify(), StandardCharsets.UTF_8),
        URLEncoder.encode(url, StandardCharsets.UTF_8)
    )

    @Keep
    @JSMethod
    open fun setTimeout(func: JSFunction?, delay: Int?): Any? {
        if (closed) return null
        func!!.hold()
        val id: Long = ++nextFunctionId
        functions.put(id, func)
        try {
            schedule(id, max(0, delay!!))
        } catch (error: IllegalStateException) {
            releaseFunction(id)
        }
        return null
    }

    @Keep
    @JSMethod
    open fun _http(url: String?, options: JSObject): JSObject? {
        val complete: JSFunction? = options.getJSFunction("complete")
        if (complete == null) return req(url, options)
        if (closed) {
            complete.release()
            return null
        }
        val id: Long = ++nextFunctionId
        functions.put(id, complete)
        try {
            val req: Req = objectFrom(options.stringify())
            val call: Call = Connect.to(url, req)
            synchronized(this) {
                if (closed) {
                    releaseFunction(id)
                    return null
                }
                calls.add(call)
            }
            call.enqueue(getCallback(id, req))
        } catch (error: RuntimeException) {
            releaseFunction(id)
            throw error
        }
        return null
    }

    @Keep
    @JSMethod
    open fun req(url: String?, options: JSObject): JSObject {
        var call: Call? = null
        try {
            val req: Req = objectFrom(options.stringify())
            call = Connect.to(url, req)
            synchronized(this) {
                if (closed) return Connect.error(ctx!!)
                calls.add(call)
            }
            return Connect.success(ctx!!, req, call.execute())
        } catch (e: Exception) {
            return Connect.error(ctx!!)
        } finally {
            synchronized(this) {
                calls.remove(call)
            }
        }
    }

    @Keep
    @JSMethod
    open fun joinUrl(parent: String?, child: String?): String = UriUtil.resolve(parent, child)

    @Keep
    @JSMethod
    open fun md5X(text: String): String {
        return md5(text)
    }

    @Keep
    @JSMethod
    open fun aesX(
        mode: String,
        encrypt: Boolean,
        input: String,
        inBase64: Boolean,
        key: String,
        iv: String?,
        outBase64: Boolean
    ): String {
        return aes(mode, encrypt, input, inBase64, key, iv, outBase64)
    }

    @Keep
    @JSMethod
    open fun rsaX(
        mode: String,
        pub: Boolean,
        encrypt: Boolean,
        input: String,
        inBase64: Boolean,
        key: String,
        outBase64: Boolean
    ): String {
        return rsa(mode, pub, encrypt, input, inBase64, key, outBase64)
    }

    private fun getCallback(id: Long, req: Req): Callback {
        return object : Callback {
            override fun onResponse(call: Call, response: Response) {
                synchronized(this@Global) {
                    calls.remove(call)
                    if (closed) {
                        response.close()
                        return
                    }
                    responses.add(response)
                    if (!submit(
                            Runnable {
                                try {
                                    complete(id, Connect.success(ctx!!, req, response))
                                } finally {
                                    synchronized(this@Global) {
                                        responses.remove(response)
                                    }
                                    response.close()
                                }
                            }
                        )
                    ) {
                        responses.remove(response)
                        response.close()
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                synchronized(this@Global) {
                    calls.remove(call)
                    submit(Runnable { complete(id, Connect.error(ctx!!)) })
                }
            }
        }
    }

    private fun complete(id: Long, result: JSObject) {
        val function: JSFunction? = functions.remove(id)
        try {
            if (function != null) function.callVoid(result)
        } finally {
            result.release()
            if (function != null) function.release()
        }
    }

    private fun releaseFunction(id: Long) {
        val function: JSFunction? = functions.remove(id)
        if (function != null) function.release()
    }

    private fun schedule(id: Long, delay: Int) {
        timer.schedule(
            object : TimerTask() {
                override fun run() {
                    submit(
                        Runnable {
                            val function: JSFunction? = functions.get(id)
                            if (function == null) return@Runnable
                            try {
                                function.callVoid()
                            } finally {
                                releaseFunction(id)
                            }
                        }
                    )
                }
            },
            delay.toLong()
        )
    }

    /** Can be called from any thread before the executor drains its cleanup task.  */
    @Synchronized
    open fun cancelPending() {
        if (closed) return
        closed = true
        timer.cancel()
        for (call: Call in calls) call.cancel()
        calls.clear()
        for (response: Response in responses) response.close()
        responses.clear()
    }

    /** Must run on the owning QuickJS thread, before destroying its context.  */
    open fun destroy() {
        cancelPending()
        for (function: JSFunction in functions.values) function.release()
        functions.clear()
    }

    // Keep generated static forwarders non-final so existing Java subclasses can hide them.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmStatic
        open fun create(ctx: QuickJSContext, executor: ExecutorService): Global {
            val global: Global = Global(ctx, executor)
            global.setProperty()
            return global
        }
    }
}
