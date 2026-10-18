package com.wuming.quickjs.utils

import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Json
import com.github.catvod.utils.Json.toMap
import com.github.catvod.utils.Util
import com.google.common.net.HttpHeaders
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext
import com.wuming.quickjs.bean.Req
import com.wuming.quickjs.utils.JSUtil.releaseFailed
import com.wuming.quickjs.utils.JSUtil.toArray
import java.security.SecureRandom
import java.util.function.Supplier
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

open class Connect {
    // Keep generated static forwarders non-final so existing Java subclasses can hide them.
    @Suppress("NON_FINAL_MEMBER_IN_OBJECT")
    companion object {
        @JvmStatic
        open fun to(url: String?, req: Req): Call {
            val client: OkHttpClient = OkHttp.client(req.isRedirect(), req.getTimeout().toLong())
            return client.newCall(getRequest(url, req))
        }

        @JvmStatic
        open fun success(ctx: QuickJSContext, req: Req, res: Response): JSObject {
            var jsObject: JSObject? = null
            try {
                res.use {
                    jsObject = ctx.createNewJSObject()
                    val jsHeader: JSObject = ctx.createNewJSObject()
                    try {
                        setHeader(ctx, res, jsHeader)
                        jsObject.setProperty("headers", jsHeader)
                    } finally {
                        jsHeader.release()
                    }
                    jsObject.setProperty("code", res.code)
                    if (req.getBuffer() == 0) {
                        jsObject.setProperty(
                            "content",
                            String(res.body.bytes(), charset(req.getCharset()))
                        )
                    }
                    if (req.getBuffer() == 1) {
                        setOwnedObject(
                            jsObject,
                            "content",
                            toArray(ctx, res.body.bytes())
                        )
                    }
                    if (req.getBuffer() ==
                        2
                    ) {
                        jsObject.setProperty("content", Util.base64(res.body.bytes()))
                    }
                    if (req.getBuffer() == 3) jsObject.setProperty("content", res.body.bytes())
                    return jsObject
                }
            } catch (e: Exception) {
                android.util.Log.w("Connect", "success body read failed: ${e.javaClass.simpleName}: ${e.message}")
                if (jsObject != null) releaseFailed(jsObject, e)
                return error(ctx)
            } catch (failure: Error) {
                if (jsObject != null) releaseFailed(jsObject, failure)
                throw failure
            }
        }

        @JvmStatic
        open fun error(ctx: QuickJSContext): JSObject = error(Supplier { ctx.createNewJSObject() })

        @JvmStatic
        open fun error(createObject: Supplier<JSObject>): JSObject {
            val jsObject: JSObject = createObject.get()
            try {
                setOwnedObject(jsObject, "headers", createObject.get())
                jsObject.setProperty("content", "")
                jsObject.setProperty("code", "")
                return jsObject
            } catch (failure: RuntimeException) {
                releaseFailed(jsObject, failure)
                throw failure
            } catch (failure: Error) {
                releaseFailed(jsObject, failure)
                throw failure
            }
        }

        private fun getRequest(url: String?, req: Req): Request {
            require(!(url == null || url.isEmpty())) { "URL cannot be null or empty" }
            val headers = (req.getHeader() ?: emptyMap()).toHeaders()
            if (req.getMethod().equals("post", ignoreCase = true)) {
                return Request.Builder().url(url).headers(headers).post(
                    getPostBody(
                        req,
                        headers.get(
                            HttpHeaders.CONTENT_TYPE
                        )
                    )
                ).build()
            } else if (req.getMethod().equals("header", ignoreCase = true)) {
                return Request.Builder().url(url).headers(headers).head().build()
            } else {
                return Request.Builder().url(url).headers(headers).get().build()
            }
        }

        private fun getPostBody(req: Req, contentType: String?): RequestBody {
            if (req.getData() != null && "json" == req.getPostType()) return getJsonBody(req)
            if (req.getData() != null && "form" == req.getPostType()) return getFormBody(req)
            if (req.getData() != null &&
                "form-data" == req.getPostType()
            ) {
                return getFormDataBody(req)
            }
            if (req.getBody() != null &&
                contentType != null
            ) {
                return req.getBody()!!.toRequestBody(contentType.toMediaType())
            }
            return ByteArray(0).toRequestBody()
        }

        private fun getJsonBody(req: Req): RequestBody = req.getData()!!.toString().toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )

        private fun getFormBody(req: Req): RequestBody {
            val builder: FormBody.Builder = FormBody.Builder()
            val params: Map<String, String> = toMap(req.getData()!!)
            for (key: String in params.keys) builder.add(key, params.get(key)!!)
            return builder.build()
        }

        private val SECURE_RANDOM = SecureRandom()

        private fun getFormDataBody(req: Req): RequestBody {
            val boundary: String =
                "--dio-boundary-" + SECURE_RANDOM.nextInt(42949) + SECURE_RANDOM.nextInt(67296)
            val builder: MultipartBody.Builder =
                MultipartBody.Builder(boundary).setType(MultipartBody.FORM)
            val params: Map<String, String> = toMap(req.getData()!!)
            for (key: String in params.keys) builder.addFormDataPart(key, params.get(key)!!)
            return builder.build()
        }

        private fun setOwnedObject(parent: JSObject, name: String?, value: JSObject) {
            try {
                parent.setProperty(name, value)
            } finally {
                value.release()
            }
        }

        private fun setHeader(ctx: QuickJSContext, res: Response, `object`: JSObject) {
            for (entry in res.headers.toMultimap().entries) {
                if (entry.value.size == 1) `object`.setProperty(entry.key, entry.value.get(0))
                if (entry.value.size >= 2) {
                    setOwnedObject(
                        `object`,
                        entry.key,
                        toArray(ctx, entry.value)
                    )
                }
            }
        }
    }
}
