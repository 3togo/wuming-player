package com.wuming.tv.core.source.packages

import java.util.Base64

/** Host identity is scoped; the provider continues to see its own original key. */
object PackageSourceIdentity {
    private const val PREFIX = "wmp:"
    @JvmStatic fun scoped(packageId: String, original: String): String {
        require(packageId.matches(Regex("[a-f0-9]{32}"))) { "Invalid package identity" }
        return "$PREFIX$packageId:" + Base64.getUrlEncoder().withoutPadding().encodeToString(original.toByteArray(Charsets.UTF_8))
    }
    @JvmStatic fun original(key: String): String {
        if (!key.startsWith(PREFIX)) return key
        val parts = key.split(':', limit = 3)
        if (parts.size != 3 || !parts[1].matches(Regex("[a-f0-9]{32}"))) return key
        return runCatching { String(Base64.getUrlDecoder().decode(parts[2]), Charsets.UTF_8) }.getOrDefault(key)
    }
}
