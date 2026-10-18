package com.wuming.tv.guardhost

import java.lang.reflect.Array
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

/** Bounded, redacted diagnostics called only by explicitly instrumented provider artifacts.  */
object GuardedProviderDiagnostics {
    private val USER_INFO: Pattern = Pattern.compile("(?i)(https?://)[^/@\\s]+@")
    private val SECRET_QUERY: Pattern = Pattern.compile(
        "(?i)([?&](?:token|auth|authorization|cookie|key|sign|signature|password|pwd)=)[^&#\\s]*"
    )
    private val BEARER: Pattern = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+=*")

    @JvmStatic
    fun reportProviderState(provider: Any?) {
        if (provider == null) return
        var emitted: Int = 0
        for (field: Field in provider.javaClass.getDeclaredFields()) {
            if (field.getType() !== String::class.java || emitted == 12) continue
            try {
                field.setAccessible(true)
                val value: String? =
                    field.get(
                        if (Modifier.isStatic(field.getModifiers())) null else provider
                    ) as String?
                System.err.println(
                    "TVBOX_GUARDED_DIAGNOSTIC state=" + limit(
                        field.getName(),
                        80
                    ) + " " + summarize(value)
                )
                emitted++
            } catch (ignored: RuntimeException) {
                // Diagnostic state is best-effort; never alter provider control flow.
            } catch (ignored: IllegalAccessException) {
            }
        }
    }

    @JvmStatic
    fun report(failure: Throwable?) {
        if (failure == null) return
        val type: String = failure.javaClass.getName()
        val message: String = redact(java.lang.String.valueOf(failure.message))
        System.err.println(
            "TVBOX_GUARDED_DIAGNOSTIC exception=" + limit(
                type,
                200
            ) + " message=" + limit(message, 800)
        )
        var emitted: Int = 0
        for (frame: StackTraceElement in failure.getStackTrace()) {
            val owner: String = frame.getClassName().lowercase(Locale.ROOT)
            if (!owner.startsWith("com.github.catvod.") && !owner.startsWith("okhttp3.")) continue
            System.err.println("TVBOX_GUARDED_DIAGNOSTIC frame=" + limit(frame.toString(), 500))
            if (++emitted == 8) break
        }
    }

    @JvmStatic
    fun reportValue(label: String, value: Any?) {
        if (value == null) {
            System.err.println("TVBOX_GUARDED_DIAGNOSTIC value=" + limit(label, 80) + " null")
            return
        }
        if (value.javaClass.isArray()) {
            val length: Int = Array.getLength(value)
            val summary: StringBuilder = StringBuilder("arrayLength=").append(length)
            for (index: Int in 0..<Math.min(length, 4)) {
                val item: Any? = Array.get(value, index)
                summary.append(" item").append(index).append("Length=")
                    .append(if (item == null) -1 else java.lang.String.valueOf(item).length)
            }
            System.err.println("TVBOX_GUARDED_DIAGNOSTIC value=" + limit(label, 80) + " " + summary)
        } else {
            System.err.println(
                "TVBOX_GUARDED_DIAGNOSTIC value=" + limit(
                    label,
                    80
                ) + " " + summarize(java.lang.String.valueOf(value))
            )
        }
    }

    private fun redact(input: String?): String {
        var value: String? = USER_INFO.matcher(input).replaceAll("$1<redacted>@")
        value = SECRET_QUERY.matcher(value).replaceAll("$1<redacted>")
        return BEARER.matcher(value).replaceAll("Bearer <redacted>")
    }

    private fun summarize(value: String?): String {
        if (value == null) return "null"
        if (value.startsWith("http://") || value.startsWith("https://")) {
            try {
                val uri: URI = URI.create(value)
                val path: String? = if (uri.getRawPath() == null) "" else uri.getRawPath()
                return "url=" + limit(uri.getScheme() + "://" + uri.getHost() + path, 500)
            } catch (ignored: IllegalArgumentException) {
                // Fall through to the non-sensitive length summary.
            }
        }
        return "length=" + value.length
    }

    private fun limit(value: String, maximum: Int): String? {
        val singleLine: String = value.replace('\r', ' ').replace('\n', ' ')
        return if (singleLine.length <= maximum) singleLine else singleLine.substring(0, maximum)
    }
}
