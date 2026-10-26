package com.wuming.tv.desktop.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

object DesktopLogging {
    private val initialized = AtomicBoolean()
    val directory: Path by lazy {
        val xdg = System.getenv("XDG_CACHE_HOME")?.trim().orEmpty()
        val base = if (xdg.isNotEmpty()) Path.of(xdg) else Path.of(System.getProperty("user.home"), ".cache")
        base.resolve("tvbox-desktop").resolve("logs")
    }

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        Files.createDirectories(directory)
        runCatching { Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------")) }
        System.setProperty(
            "java.util.logging.SimpleFormatter.format",
            "%1\$tFT%1\$tT.%1\$tL%1\$tz %4\$-7s [%3\$s] %5\$s%6\$s%n",
        )
        val handler = FileHandler(directory.resolve("tvbox-desktop-%g.log").toString(), 2 * 1024 * 1024, 4, true).apply {
            level = Level.ALL
            formatter = SimpleFormatter()
        }
        runCatching {
            Files.list(directory).use { files ->
                files.filter { it.fileName.toString().startsWith("tvbox-desktop-") }
                    .forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rw-------")) }
            }
        }
        Logger.getLogger("").addHandler(handler)
        Logger.getLogger("").level = Level.INFO
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            Logger.getLogger("tvbox.uncaught").log(Level.SEVERE, "uncaught thread=${thread.name}", failure)
        }
        Logger.getLogger("tvbox.session").info("log directory=${directory.toAbsolutePath()}")
        Logger.getLogger("tvbox.session").info(
            "start time=${Instant.now()} java=${System.getProperty("java.version")} os=${System.getProperty("os.name")} " +
                "arch=${System.getProperty("os.arch")} headless=${java.awt.GraphicsEnvironment.isHeadless()}",
        )
    }
}
