package com.wuming.tv.core.source

/** Integrity gate for a downloaded provider JAR and its last initialized copy. */
object ProviderJarRecoveryPolicy {
    @JvmStatic
    fun matchesExpected(digest: SpiderDigest?, md5: String, sha256: String): Boolean {
        if (digest == null) return true
        val expected = digest.value.trim()
        val actual = if (digest.kind == SpiderDigestKind.MD5) md5 else sha256
        val length = if (digest.kind == SpiderDigestKind.MD5) 32 else 64
        return expected.length == length && expected.all { it.isHexDigit() } &&
            actual.equals(expected, ignoreCase = true)
    }

    @JvmStatic
    fun mayRecover(digest: SpiderDigest?, md5: String, sha256: String, recordedSha256: String?): Boolean =
        recordedSha256?.length == 64 && recordedSha256.all { it.isHexDigit() } &&
            sha256.equals(recordedSha256, ignoreCase = true) && matchesExpected(digest, md5, sha256)

    private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
