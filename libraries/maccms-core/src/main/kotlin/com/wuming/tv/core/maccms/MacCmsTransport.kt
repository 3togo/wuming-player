package com.wuming.tv.core.maccms

/** Performs one collection-API GET and returns the decoded response body. */
fun interface MacCmsTransport {

    fun get(api: String, headers: Map<String, String>, params: Map<String, String>): String
}
