package com.wuming.android.tv.utils

import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ClickableSpan
import com.wuming.android.tv.api.config.RuleConfig
import com.wuming.android.tv.bean.Result
import com.wuming.android.tv.bean.Rule
import com.wuming.tv.core.text.RegexUtils
import com.github.catvod.utils.Json
import com.github.catvod.utils.Trans
import com.github.catvod.utils.Util
import java.util.function.Function
import java.util.regex.Pattern

object Sniffer {
    @JvmField
    val CLICKER: Pattern = Pattern.compile("\\[a=cr:(\\{.*?\\})\\/](.*?)\\[\\/a]")

    @JvmField
    val AI_PUSH: Pattern = Pattern.compile("(https?|video):\\S+")

    @JvmField
    val SNIFFER: Pattern = Pattern.compile("https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\\?.*)?|https?://.*?video/tos[^\\s]*|rtmp:[^\\s]+")

    @JvmStatic
    fun getUrl(text: String): String {
        if (Json.isObj(text) || text.contains("$")) return text
        val m = AI_PUSH.matcher(text)
        if (m.find()) return m.group(0).orEmpty()
        return text
    }

    @JvmStatic
    fun isVideoFormat(url: String): Boolean {
        val rule = getRule(UrlUtil.uri(url))
        for (exclude in rule.getExclude()) {
            try {
                if (RegexUtils.getCachedPattern(exclude).matcher(url).find()) return false
            } catch (e: Exception) {
                if (url.contains(exclude)) return false
            }
        }
        for (regex in rule.getRegex()) {
            try {
                if (RegexUtils.getCachedPattern(regex).matcher(url).find()) return true
            } catch (e: Exception) {
                if (url.contains(regex)) return true
            }
        }
        if (url.contains("url=http") || url.contains("v=http") || url.contains(".html")) return false
        return SNIFFER.matcher(url).find()
    }

    @JvmStatic
    fun getScript(uri: Uri): List<String> = getRule(uri).getScript()

    private fun getRule(uri: Uri): Rule {
        if (uri.host == null) return Rule.empty()
        val hosts = TextUtils.join(",", listOf(UrlUtil.host(uri), UrlUtil.host(uri.getQueryParameter("url"))))
        for (rule in RuleConfig.get().rules) for (host in rule.getHosts()) if (Util.containOrMatch(hosts, host)) return rule
        return Rule.empty()
    }

    @JvmStatic
    fun buildClickable(text: String, factory: Function<Result, ClickableSpan>): SpannableStringBuilder {
        val span = SpannableStringBuilder()
        val matcher = CLICKER.matcher(text)
        var last = 0
        while (matcher.find()) {
            span.append(text, last, matcher.start())
            val start = span.length
            span.append(Trans.s2t(matcher.group(2).orEmpty().trim()))
            span.setSpan(factory.apply(Result.type(matcher.group(1).orEmpty())), start, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = matcher.end()
        }
        span.append(text, last, text.length)
        return span
    }
}
