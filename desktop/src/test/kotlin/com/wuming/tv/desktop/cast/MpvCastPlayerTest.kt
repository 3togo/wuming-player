package com.wuming.tv.desktop.cast

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.*
import java.io.ByteArrayInputStream

class MpvCastPlayerTest {
    @Test fun realMpvReceivesHttpMediaAndAcceptsControls() {
        val executable = Path.of("/usr/bin/mpv")
        assumeTrue("mpv required for integration test", Files.isExecutable(executable))
        val file = Files.createTempFile("cast-mpv", ".wav")
        val format = AudioFormat(8000f, 16, 1, true, false)
        AudioInputStream(ByteArrayInputStream(ByteArray(8000 * 2 * 10)), format, 80000).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, file.toFile())
        }
        try {
            CastFileServer(file, "127.0.0.1").use { media ->
                MpvCastPlayer(executable, listOf("--vo=null", "--ao=null")).use { player ->
                    player.load(media.url())
                    player.play()
                    await { player.snapshot().state == "PLAYING" && player.snapshot().duration > 0 }
                    player.pause()
                    await { player.snapshot().state == "PAUSED_PLAYBACK" }
                    player.seek(3.0)
                    await { player.snapshot().position >= 2.9 }
                    player.volume(25)
                    player.mute(true)
                    await { player.snapshot().volume == 25 && player.snapshot().muted }
                    player.stop()
                    await { player.snapshot().state == "STOPPED" }
                }
            }
        } finally { Files.deleteIfExists(file) }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) Thread.sleep(25)
        assertTrue("mpv state update timed out", condition())
    }
}
