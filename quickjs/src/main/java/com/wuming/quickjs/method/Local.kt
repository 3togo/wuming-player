package com.wuming.quickjs.method

import android.text.TextUtils
import androidx.annotation.Keep
import com.github.catvod.utils.Prefers
import com.whl.quickjs.wrapper.JSMethod

class Local {

    private fun getKey(rule: String, key: String): String =
        "cache_" + (if (TextUtils.isEmpty(rule)) "" else "${rule}_") + key

    @Keep
    @JSMethod
    fun get(rule: String, key: String): String = Prefers.getString(getKey(rule, key))!!

    @Keep
    @JSMethod
    fun set(rule: String, key: String, value: String) {
        Prefers.put(getKey(rule, key), value)
    }

    @Keep
    @JSMethod
    fun delete(rule: String, key: String) {
        Prefers.remove(getKey(rule, key))
    }
}
