package com.wuming.android.tv.bean

import android.text.TextUtils
import com.google.gson.annotations.SerializedName
import com.wuming.tv.core.source.GitHubMirror

class GitHubProxy @JvmOverloads constructor(
    @SerializedName("name") var name: String? = null,
    @SerializedName("host") var host: String? = null,
    @SerializedName("note") var note: String? = null
) {
    fun isNone(): Boolean = BeanUtils.isEmpty(host)

    fun buildProxyUrl(githubUrl: String): String = GitHubMirror.resolve(githubUrl, host.orEmpty())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as GitHubProxy
        return TextUtils.equals(host, that.host)
    }

    override fun hashCode(): Int = host?.hashCode() ?: 0

    override fun toString(): String = "GitHubProxy{name='$name', host='$host', note='$note'}"

    class `List` {
        @SerializedName("version")
        var version: Int = 0

        @SerializedName("updated")
        var updated: String? = null

        @SerializedName("proxies")
        var proxies: kotlin.collections.List<GitHubProxy>? = null
            get() = field ?: listOf()

        @SerializedName("test_url")
        var testUrl: String? = null
    }
}
