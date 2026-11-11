package com.wuming.tv.core.playback

/** Reports conflicts; mpv remains responsible for interpreting its full configuration grammar. */
object MpvConfigurationPolicy {
    private val profilePattern = Regex("\\[([^]]*)]\\s*(?:#.*)?")
    private val optionPattern = Regex("(?:--)?([A-Za-z0-9_-]+)\\s*(?:=.*|#.*)?")
    // Keep this diagnostic list aligned with desktop ExternalPlayerAdapter.command.
    val managedOptions: Set<String> = setOf("title-bar", "border", "window-dragging", "osc", "wid", "vo",
        "gpu-context", "input-vo-keyboard", "input-default-bindings", "fullscreen", "speed", "idle",
        "keep-open", "keep-open-pause", "force-window", "input-ipc-server", "force-media-title",
        "user-agent", "http-header-fields")

    @JvmStatic
    fun defaultOptions(content: String): Set<String> {
        var defaultProfile = true
        val options = linkedSetOf<String>()
        for (raw in content.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) continue
            val profile = profilePattern.matchEntire(line)
            if (profile != null) { defaultProfile = profile.groupValues[1] in setOf("", "default"); continue }
            if (!defaultProfile) continue
            val match = optionPattern.matchEntire(line) ?: continue
            options += match.groupValues[1].removePrefix("no-")
        }
        return options
    }

    @JvmStatic
    fun conflicts(content: String): List<String> = defaultOptions(content).intersect(managedOptions).sorted()
}
