package com.wuming.android.tv.media.subtitle

import com.github.catvod.net.OkHttp
import com.wuming.tv.core.media.subtitle.AssrtSubtitleCodec
import com.wuming.tv.core.media.subtitle.SubtitleCandidate
import com.wuming.tv.core.media.subtitle.SubtitlePage
import com.wuming.tv.core.media.subtitle.SubtitleProvider
import com.wuming.tv.core.media.subtitle.SubtitleQuery
import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.ResponseBody

/** Official token-authenticated assrt.net API adapter; no HTML scraping is involved. */
class AssrtSubtitleProvider(token: String?) : SubtitleProvider {
    private val token = token?.trim { it <= ' ' } ?: ""

    init { require(this.token.isNotEmpty()) { "Assrt API token is required" } }

    override fun id(): String = ID

    @Throws(Exception::class)
    override fun search(query: SubtitleQuery): SubtitlePage {
        val count = minOf(15, query.pageSize())
        val url = requireBase("search").newBuilder()
            .addQueryParameter("q", query.title())
            .addQueryParameter("pos", ((query.page() - 1) * count).toString())
            .addQueryParameter("cnt", count.toString()).build()
        return parseSearch(execute(url), query.page())
    }

    @Throws(Exception::class)
    override fun resolve(candidate: SubtitleCandidate?): SubtitleCandidate {
        require(candidate != null && candidate.providerId() == ID) { "Candidate does not belong to Assrt" }
        val url = requireBase("detail").newBuilder().addQueryParameter("id", candidate.id()).build()
        return parseDetail(execute(url), candidate)
    }

    private fun execute(url: HttpUrl): String {
        OkHttp.newCall(url.toString(), mapOf("Authorization" to "Bearer $token")).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Assrt HTTP " + response.code)
            return responseText(response.body)
        }
    }

    private fun responseText(body: ResponseBody?): String =
        body?.string() ?: throw IOException("Assrt returned an empty response")

    companion object {
        const val ID = AssrtSubtitleCodec.ID
        private const val API = "https://api.assrt.net/v1/sub/"

        @JvmStatic
        @Throws(IOException::class)
        fun parseSearch(json: String, page: Int): SubtitlePage = AssrtSubtitleCodec.parseSearch(json, page)

        @JvmStatic
        @Throws(IOException::class)
        fun parseDetail(json: String, candidate: SubtitleCandidate): SubtitleCandidate = AssrtSubtitleCodec.parseDetail(json, candidate)

        private fun requireBase(path: String): HttpUrl =
            (API + path).toHttpUrlOrNull() ?: throw IllegalStateException("Invalid Assrt API base URL")
    }
}
