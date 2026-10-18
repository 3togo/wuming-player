package com.wuming.tv.core.runtime.jvmspider

data class JvmSpiderSiteSpec(
    val key: String,
    val name: String,
    val api: String,
    val extension: String = "",
    val artifactLocation: String,
    val categories: List<String> = emptyList(),
    val providerKey: String = key,
) {
    init {
        require(key.isNotBlank()) { "Spider source key is required" }
        require(api.removePrefix("csp_").matches(Regex("[A-Za-z0-9_$]+"))) {
            "JVM spider API must be a simple CatVod class name"
        }
        require(artifactLocation.isNotBlank()) { "Spider artifact location is required" }
    }

    val className: String get() = "com.github.catvod.spider.${api.removePrefix("csp_")}"

    override fun toString(): String =
        "JvmSpiderSiteSpec(key=$key, name=$name, api=$api, extension=<redacted>, artifactLocation=<redacted>)"
}

data class JvmSpiderArtifactReport(
    val sha256: String,
    val classCount: Int,
    val uncompressedBytes: Long,
)

class UnsupportedJvmSpiderArtifact(message: String) : IllegalArgumentException(message)
