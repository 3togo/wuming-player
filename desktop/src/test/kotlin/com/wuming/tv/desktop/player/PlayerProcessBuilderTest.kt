package com.wuming.tv.desktop.player

import org.junit.Assert.*
import org.junit.Test

class PlayerProcessBuilderTest {
    @Test fun retainsActualPlayerCommand() {
        val command = listOf("/usr/bin/mpv", "--user-agent=KHTML, like Gecko", "http://127.0.0.1/video")
        assertEquals(command, playerProcessBuilder(command).command())
    }

    @Test fun leavesOtherProcessesEnvironmentUnchanged() {
        assertEquals(ProcessBuilder("/usr/bin/vlc").environment(), playerProcessBuilder(listOf("/usr/bin/vlc")).environment())
    }
}
