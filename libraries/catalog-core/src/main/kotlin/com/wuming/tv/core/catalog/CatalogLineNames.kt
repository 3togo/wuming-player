package com.wuming.tv.core.catalog

/** Provider padding is presentation data; the original line flag remains the playback key. */
object CatalogLineNames {
    private val padding = Regex("\u0620{3,}$")

    fun display(name: String, flag: String = ""): String =
        padding.replace(name.ifBlank { flag }.trimEnd(), "").trimEnd().ifBlank { "Line" }
}
