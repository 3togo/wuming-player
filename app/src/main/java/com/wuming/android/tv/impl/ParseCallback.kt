package com.wuming.android.tv.impl

interface ParseCallback {
    fun onParseSuccess(headers: Map<String, String>?, url: String?, from: String?)
    fun onParseError()
}
