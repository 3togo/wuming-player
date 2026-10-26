package com.wuming.tv.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class DesktopInstanceGuardTest {
    @Test fun excludesOtherProcessesUntilExitAndAllowsExplicitOptOut() {
        val directory = Files.createTempDirectory("desktop-instance-test-")
        val path = directory.resolve("instance.lock")
        try {
            DesktopInstanceGuard.acquire(false, path)!!.use {
                assertEquals("blocked", probe(path, false))
                assertEquals("allowed", probe(path, true))
                assertEquals("blocked", probe(path, false))
            }
            assertEquals("allowed", probe(path, false))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun oldLockFileWithoutAnOwnerDoesNotBlockStartup() {
        val directory = Files.createTempDirectory("desktop-instance-stale-")
        val path = directory.resolve("instance.lock")
        try {
            Files.writeString(path, "old process")
            assertEquals("allowed", probe(path, false))
            assertEquals("old process", Files.readString(path))
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun probe(path: Path, allowMultiple: Boolean): String {
        val classpath = listOf(DesktopInstanceGuard::class.java, DesktopInstanceProbe::class.java, Unit::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
            .joinToString(java.io.File.pathSeparator)
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", classpath, DesktopInstanceProbe::class.java.name, path.toString(), allowMultiple.toString())
            .redirectErrorStream(true).start()
        try {
            assertTrue("Instance probe timed out", process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.exitValue())
            return output.lineSequence().single { it.startsWith("RESULT:") }.removePrefix("RESULT:")
        } finally { process.destroyForcibly() }
    }
}

object DesktopInstanceProbe {
    @JvmStatic fun main(args: Array<String>) {
        val guard = DesktopInstanceGuard.acquire(args[1].toBoolean(), Path.of(args[0]))
        println("RESULT:${if (guard == null) "blocked" else "allowed"}")
        guard?.close()
    }
}
