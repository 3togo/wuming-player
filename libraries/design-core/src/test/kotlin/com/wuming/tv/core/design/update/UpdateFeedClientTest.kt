package com.wuming.tv.core.design.update

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class UpdateFeedClientTest {
    @Test fun unconfiguredPublisherDoesNotContactAnyFeed() {
        val client = http { throw AssertionError("Unconfigured publisher made a network request") }
        assertNull(UpdateFeedClient({ null }).check(emptyList(), "", "debug", "mobile",
            "arm64_v8a", "fixture", 1, client))
    }
    private fun manifest(code: Int) = """{"schema":1,"channel":"debug","code":$code,
        "artifacts":[{"mode":"mobile","abi":"arm64_v8a","packageName":"fixture",
        "url":"https://github.com/3togo/TV-updates/releases/download/v/mobile.apk",
        "sha256":"${"a".repeat(64)}","certificateSha256":"${"b".repeat(64)}"}]}"""
    private fun http(body: (String) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, text) = body(chain.request().url.host)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
            .message("fixture").body(text.toResponseBody()).build()
    }.build()
    private fun check(client: OkHttpClient) = UpdateFeedClient({ null }).check(
        listOf("https://old.example/feed", "https://new.example/feed"), "3togo/TV-updates",
        "debug", "mobile", "arm64_v8a", "fixture", 1, client)
    @Test fun newerFeedWinsAndUnavailableMirrorDoesNotMaskValidFeed() {
        assertEquals(3, check(http { 200 to manifest(if (it == "old.example") 2 else 3) })!!.code)
        assertEquals(3, check(http { if (it == "old.example") 503 to "unavailable" else 200 to manifest(3) })!!.code)
    }
    @Test fun oversizedOrInvalidMetadataCannotBecomeUpdateCandidate() {
        assertThrows(IOException::class.java) { check(http { 200 to "x".repeat(1024 * 1024 + 1) }) }
        assertThrows(IOException::class.java) { check(http { 200 to "<html>error</html>" }) }
    }
}
