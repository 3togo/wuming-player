package com.wuming.quickjs.method

import android.util.Log
import com.whl.quickjs.wrapper.QuickJSContext

class Console : QuickJSContext.Console {

    override fun log(info: String) {
        Log.d(TAG, info)
    }

    override fun info(info: String) {
        Log.i(TAG, info)
    }

    override fun warn(info: String) {
        Log.w(TAG, info)
    }

    override fun error(info: String) {
        Log.e(TAG, info)
    }

    companion object {
        private const val TAG = "quickjs"
    }
}
