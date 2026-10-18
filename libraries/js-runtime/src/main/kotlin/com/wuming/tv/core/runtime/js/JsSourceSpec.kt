package com.wuming.tv.core.runtime.js

/** Sensitive provider extension data is deliberately excluded from generated toString output. */
class JsSourceSpec(
    val key: String,
    val name: String,
    val moduleUrl: String,
    val extension: String,
    val categories: List<String> = emptyList(),
    val providerKey: String = key,
    val storageIdentity: String = moduleUrl,
) {
    init {
        require(key.isNotBlank()) { "Source key is required" }
        require(moduleUrl.startsWith("http://") || moduleUrl.startsWith("https://")) {
            "JavaScript module must use HTTP(S)"
        }
    }

    override fun toString(): String = "JsSourceSpec(key=$key, name=$name, moduleUrl=<redacted>, extension=<redacted>)"
}
