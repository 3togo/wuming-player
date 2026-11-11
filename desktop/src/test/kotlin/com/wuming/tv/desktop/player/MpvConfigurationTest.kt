package com.wuming.tv.desktop.player

import com.wuming.tv.desktop.config.DesktopMpvConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class MpvConfigurationTest {
    @Test fun atomicSaveFailedImportAndLaunchPrecedence() {
        val directory = Files.createTempDirectory(Files.createDirectories(Path.of(System.getProperty("user.home"), "temp")), "mpv-config-")
        try {
            val config = DesktopMpvConfiguration(directory.resolve("with space/mpv.conf"))
            assertEquals(emptyList<String>(), config.launchArguments())
            config.save("slang=fr\nborder=yes\n")
            val malformed = directory.resolve("bad.conf")
            Files.write(malformed, byteArrayOf(0xc3.toByte(), 0x28))
            assertThrows(Exception::class.java) { config.importFrom(malformed) }
            assertEquals("slang=fr\nborder=yes\n", config.read())
            assertThrows(IllegalArgumentException::class.java) { config.save("bad\u0000value") }
            val player = ExternalPlayerAdapter(Path.of("/usr/bin/mpv"), ProcessStarter { null },
                mpvConfiguration = config, subtitleLocale = { Locale.forLanguageTag("zh-TW") })
            val args = player.command("https://example.test/a", emptyMap())
            assertEquals(listOf("--slang-append=zh-TW", "--slang-append=zh-Hant", "--slang-append=zh"), args.filter { it.startsWith("--slang-append=") })
            assertTrue(args.indexOf("--slang-append=zh") < args.indexOf("--include=${config.path}"))
            assertTrue(args.indexOf("--include=${config.path}") < args.indexOf("--border=no"))
            assertEquals("https://example.test/a", args.last())
            val oversized = directory.resolve("oversized.conf")
            Files.write(oversized, ByteArray(DesktopMpvConfiguration.MAX_BYTES + 1))
            assertThrows(IllegalArgumentException::class.java) { config.importFrom(oversized) }
            assertEquals("slang=fr\nborder=yes\n", config.read())
            val valid = directory.resolve("valid.conf")
            Files.writeString(valid, "deband=yes")
            config.importFrom(valid)
            assertEquals("deband=yes", config.read())
            Files.list(config.path.parent).use { assertEquals(1, it.count()) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
