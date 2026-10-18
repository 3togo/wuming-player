package com.wuming.tv.desktop.ui

import java.util.concurrent.TimeUnit

/** Linux desktop port. Commands are bounded and never run on Swing's event thread. */
internal class LinuxSystemAppearance(
    private val command: (List<String>) -> String? = ::readCommand,
    private val gtkTheme: () -> String? = { System.getenv("GTK_THEME") },
) {
    fun isDark(): Boolean {
        val portal = command(listOf("gdbus", "call", "--session", "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop", "--method", "org.freedesktop.portal.Settings.Read",
            "org.freedesktop.appearance", "color-scheme"))
        parsePortal(portal)?.let { return it }
        val scheme = command(listOf("gsettings", "get", "org.gnome.desktop.interface", "color-scheme"))
            ?.trim()?.trim('\'')
        if (scheme == "prefer-dark") return true
        if (scheme == "prefer-light") return false
        val theme = gtkTheme() ?: command(listOf("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"))
        return theme?.contains("dark", ignoreCase = true) ?: false
    }

    companion object {
        internal fun parsePortal(output: String?): Boolean? = when (
            Regex("uint32\\s+([012])\\b").find(output.orEmpty())?.groupValues?.get(1)
        ) {
            "1" -> true
            "2" -> false
            else -> null // No preference: consult the toolkit settings.
        }
    }
}

private fun readCommand(arguments: List<String>): String? = runCatching {
    val process = ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        if (!process.waitFor(1500, TimeUnit.MILLISECONDS) || process.exitValue() != 0) null
        else process.inputStream.bufferedReader().use { it.readText() }
    } finally {
        if (process.isAlive) process.destroyForcibly()
        process.inputStream.close()
    }
}.getOrNull()
