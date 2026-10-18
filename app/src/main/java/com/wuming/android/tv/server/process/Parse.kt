package com.wuming.android.tv.server.process

import com.wuming.android.tv.server.Nano
import com.wuming.android.tv.server.impl.Process
import com.github.catvod.utils.Asset
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.MIME_HTML
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse

open class Parse : Process {
    override fun isRequest(
        session: IHTTPSession,
        url: String,
    ): Boolean = url.startsWith("/parse")

    @Suppress("DEPRECATION")
    override fun doResponse(
        session: IHTTPSession,
        url: String,
        files: Map<String, String>,
    ): Response =
        try {
            val params = session.parms
            val html = String.format(Asset.read("parse.html"), params["jxs"], params["url"])
            newFixedLengthResponse(Status.OK, MIME_HTML, html)
        } catch (e: Exception) {
            Nano.error(e.message)
        }
}
