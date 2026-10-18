package com.wuming.android.tv.server.impl

import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

interface Process {
    fun isRequest(session: IHTTPSession, url: String): Boolean

    fun doResponse(session: IHTTPSession, url: String, files: Map<String, String>): Response
}
