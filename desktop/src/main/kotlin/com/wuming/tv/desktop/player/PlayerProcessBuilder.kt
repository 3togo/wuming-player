package com.wuming.tv.desktop.player

import com.wuming.tv.core.playback.PlaybackProxyBypassPolicy
import java.nio.file.Path

/** Applies media routing only to this player child, preserving the desktop environment. */
internal fun playerProcessBuilder(command: List<String>): ProcessBuilder {
    val builder = ProcessBuilder(command)
    if (Path.of(command.first()).fileName.toString().equals("mpv", true)) {
        val environment = builder.environment()
        environment.putAll(PlaybackProxyBypassPolicy.playerEnvironment(environment))
    }
    return builder
}
