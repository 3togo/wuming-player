package com.wuming.tv.guardwebview

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebViewBridgeProtocolTest {
    @Test fun noInterceptionPreservesNormalWebViewLoading() {
        val response = WebViewBridgeProtocol.interceptResp("request", null, null, null)
        assertNull(WebViewBridgeProtocol.interceptBody(response))
        assertNull(WebViewBridgeProtocol.interceptBody(JSONObject(
            """{"t":"intercept_resp","id":"request","body":null}""",
        )))
    }

    @Test fun intentionallyEmptyResponseStillIntercepts() {
        val response = WebViewBridgeProtocol.interceptResp("request", "text/html", "UTF-8", "")
        assertEquals("", WebViewBridgeProtocol.interceptBody(response))
    }

    @Test fun customResponsePreservesEncodedBytes() {
        val encodedHtml = "PGh0bWw+QnJpZGdlPC9odG1sPg=="
        val response = WebViewBridgeProtocol.interceptResp("request", "text/html", "UTF-8", encodedHtml)
        assertEquals(encodedHtml, WebViewBridgeProtocol.interceptBody(response))
    }
}
