package com.wuming.quickjs.utils

import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.QuickJSContext

object JSUtil {

    @JvmStatic
    fun toArray(ctx: QuickJSContext, items: List<String>?): JSArray {
        return fillArray(ctx.createNewJSArray(), items)
    }

    @JvmStatic
    fun toArray(ctx: QuickJSContext, bytes: ByteArray?): JSArray {
        return fillArray(ctx.createNewJSArray(), bytes)
    }

    @JvmStatic
    fun toObject(ctx: QuickJSContext, map: Map<String, String>?): JSObject {
        return fillObject(ctx.createNewJSObject(), map)
    }

    @JvmStatic
    fun fillArray(array: JSArray, items: List<String>?): JSArray {
        try {
            if (items != null) for (i in items.indices) array.set(items[i], i)
            return array
        } catch (failure: RuntimeException) {
            releaseFailed(array, failure)
            throw failure
        } catch (failure: Error) {
            releaseFailed(array, failure)
            throw failure
        }
    }

    @JvmStatic
    fun fillArray(array: JSArray, bytes: ByteArray?): JSArray {
        try {
            if (bytes != null) for (i in bytes.indices) array.set(bytes[i].toInt(), i)
            return array
        } catch (failure: RuntimeException) {
            releaseFailed(array, failure)
            throw failure
        } catch (failure: Error) {
            releaseFailed(array, failure)
            throw failure
        }
    }

    @JvmStatic
    fun fillObject(obj: JSObject, map: Map<String, String>?): JSObject {
        try {
            if (map != null) for (key in map.keys) obj.setProperty(key, map[key])
            return obj
        } catch (failure: RuntimeException) {
            releaseFailed(obj, failure)
            throw failure
        } catch (failure: Error) {
            releaseFailed(obj, failure)
            throw failure
        }
    }

    @JvmStatic
    fun releaseFailed(obj: JSObject, failure: Throwable) {
        try {
            obj.release()
        } catch (cleanup: RuntimeException) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        } catch (cleanup: Error) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
    }
}
