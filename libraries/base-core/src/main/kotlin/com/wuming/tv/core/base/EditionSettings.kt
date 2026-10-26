package com.wuming.tv.core.base

import java.util.Properties

/** Build-selected identity shared by Android and desktop, including helper launch targets. */
data class EditionSettings(
    val applicationId: String,
    val helperSuffix: String,
    val nativeProtocols: Boolean,
    val pushSchemes: List<String>,
) {
    fun helperPackage(name: String): String = "com.wuming.tv.$name.$helperSuffix"
    fun helperComponent(name: String): String = "${helperPackage(name)}/com.wuming.tv.$name.MainActivity"

    companion object {
        @JvmField val current: EditionSettings = from(Properties().apply {
            EditionSettings::class.java.getResourceAsStream("/wuming-edition.properties")?.use { load(it) }
        })

        internal fun from(values: Properties): EditionSettings = EditionSettings(
            values.getProperty("applicationId", "com.wuming.android.tv.publicedition"),
            values.getProperty("helperSuffix", "publicedition"),
            values.getProperty("nativeProtocols", "false").toBooleanStrict(),
            values.getProperty("pushSchemes", "http,https,video").split(','),
        )
    }
}
