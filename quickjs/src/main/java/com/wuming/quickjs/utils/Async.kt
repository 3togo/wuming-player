package com.wuming.quickjs.utils

import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import java.util.concurrent.CompletableFuture

class Async private constructor() {

    private var future: CompletableFuture<Any?> = CompletableFuture()

    private val success = JSCallFunction { args ->
        val value: Any? = if (args != null && args.isNotEmpty()) args[0] else null
        if (!future.complete(value) && value is JSObject) value.release()
        null
    }

    private val error = JSCallFunction { args ->
        val value: Any? = if (args != null && args.isNotEmpty()) args[0] else null
        try {
            future.completeExceptionally(Exception(if (value == null) "" else value.toString()))
        } finally {
            if (value is JSObject) value.release()
        }
        null
    }

    private fun call(obj: JSObject, name: String, vararg args: Any?): CompletableFuture<Any?> {
        val func = obj.getJSFunction(name)
        if (func == null) return empty()
        call(func, *args)
        return future
    }

    private fun empty(): CompletableFuture<Any?> {
        future.complete(null)
        return future
    }

    private fun call(func: JSFunction, vararg args: Any?) {
        try {
            val result = func.call(*args)
            if (result is JSObject) then(result)
            else future.complete(result)
        } catch (e: Throwable) {
            future.completeExceptionally(e)
        } finally {
            func.release()
        }
    }

    private fun then(promise: JSObject) {
        var transferred = false
        try {
            val then = promise.getJSFunction("then")
            if (then == null) {
                transferred = future.complete(promise)
            } else {
                consume(then, success)
                consume(promise.getJSFunction("catch"), error)
            }
        } finally {
            if (!transferred) promise.release()
        }
    }

    private fun consume(func: JSFunction?, callback: JSCallFunction) {
        if (func == null) return
        try {
            func.callVoid(callback)
        } finally {
            func.release()
        }
    }

    companion object {

        @JvmStatic
        fun run(obj: JSObject, name: String, vararg args: Any?): CompletableFuture<Any?> {
            return Async().call(obj, name, *args)
        }
    }
}
