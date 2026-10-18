package com.wuming.tv.core.runtime.jvmspider

import com.google.gson.Gson
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class JvmSpiderHostProcess(
    artifact: Path,
    className: String,
    extension: String,
    providerKey: String = "",
) : Closeable {

    private val gson = Gson()
    private val readerExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tvbox-jvm-spider-response").apply { isDaemon = true }
    }
    private val process: Process
    private val input: java.io.BufferedWriter
    private val output: BufferedReader

    init {
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        val classPath = System.getProperty("java.class.path") + java.io.File.pathSeparator + artifact.toAbsolutePath()
        process = ProcessBuilder(
            javaExecutable.toString(),
            "-Xms16m",
            "-Xmx256m",
            "-Dfile.encoding=UTF-8",
            "-cp",
            classPath,
            JvmSpiderHostMain::class.java.name,
            className,
            providerKey,
        ).start()
        input = process.outputWriter(StandardCharsets.UTF_8)
        output = process.inputReader(StandardCharsets.UTF_8)
        Thread({
            InputStreamReader(process.errorStream, StandardCharsets.UTF_8).use { errors ->
                val buffer = CharArray(1_024)
                while (errors.read(buffer) >= 0) {
                    // Drain provider diagnostics so the child process cannot block on stderr.
                }
            }
        }, "tvbox-jvm-spider-errors").apply { isDaemon = true }.start()
        try {
            request(JvmSpiderHostRequest("init", extension = extension))
        } catch (failure: Throwable) {
            // This instance never becomes reachable to the owner when the constructor throws,
            // so nobody can call close(): kill the child JVM here or it survives the app.
            destroy()
            throw failure
        }
    }

    /** Closes the streams and kills the child JVM; safe to call from a failed constructor. */
    private fun destroy() {
        runCatching { input.close() }
        runCatching { output.close() }
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        readerExecutor.shutdownNow()
    }

    @Synchronized
    fun request(request: JvmSpiderHostRequest): String {
        check(process.isAlive) { "JVM spider host is not running" }
        input.write(gson.toJson(request))
        input.newLine()
        input.flush()
        val future = readerExecutor.submit<String?> { output.readLine() }
        val line = try {
            future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            future.cancel(true)
            process.destroyForcibly()
            throw IllegalArgumentException("JVM spider host timed out or stopped", failure)
        } ?: throw IllegalArgumentException("JVM spider host stopped without a response")
        val response = runCatching { gson.fromJson(line, JvmSpiderHostResponse::class.java) }
            .getOrElse { throw IllegalArgumentException("JVM spider host returned invalid JSON") }
        if (!response.ok) throw IllegalArgumentException(response.error ?: "JVM spider operation failed")
        return response.result.orEmpty()
    }

    override fun close() {
        if (process.isAlive) runCatching { request(JvmSpiderHostRequest("close")) }
        destroy()
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", true)

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 40L
    }
}
