package com.wuming.tv.desktop.net

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * Persists the proxy chosen by `--scan-proxy --apply` so the desktop app (GUI or CLI) uses it on the next
 * launch without needing `-Dtvbox.ghproxy`. Stored as a standard Java properties file under the user's
 * XDG-style config directory.
 */
object DesktopProxySettings {

    val path: Path = Path.of(
        System.getProperty("user.home"),
        ".config",
        "tvbox-desktop",
        "settings.properties",
    )

    /** The persisted `tvbox.ghproxy` value, or null when nothing has been saved yet. */
    fun persistedProxy(): String? {
        if (!Files.exists(path)) return null
        return runCatching {
            Files.newBufferedReader(path).use { reader ->
                Properties().apply { load(reader) }.getProperty("tvbox.ghproxy")?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    fun persist(proxy: String) {
        runCatching { Files.createDirectories(path.parent) }
        val properties = Properties().apply {
            if (Files.exists(path)) Files.newBufferedReader(path).use { load(it) }
            setProperty("tvbox.ghproxy", proxy)
        }
        Files.newBufferedWriter(path).use { properties.store(it, "TVBox Desktop settings") }
    }
}
