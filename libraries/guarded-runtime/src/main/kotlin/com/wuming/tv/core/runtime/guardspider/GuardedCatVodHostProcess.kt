package com.wuming.tv.core.runtime.guardspider

import com.google.gson.Gson
import java.io.BufferedReader
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.net.URI
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

internal class GuardedCatVodHostProcess(
    artifact: PreparedGuardedSpiderArtifact,
    spec: GuardedCatVodSiteSpec,
) : Closeable {
    private val logger = Logger.getLogger("tvbox.guarded.host")
    private val gson = Gson()
    private val readerExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "tvbox-guarded-catvod-response").apply { isDaemon = true }
    }
    private val diagnostics = ArrayDeque<String>()
    private val process: Process
    private val input: java.io.BufferedWriter
    private val output: BufferedReader

    init {
        val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        val dataDirectory = GuardedSpiderCachePaths.root()
            .resolve("guarded-catvod-data")
            .resolve(artifact.packageSha256)
            .resolve(artifact.abi.configName)
            .resolve(safeSegment(spec.key))
        Files.createDirectories(dataDirectory)
        val command = mutableListOf(
            java.toString(),
            "-Xms16m",
            "-Xmx384m",
            "-Dfile.encoding=UTF-8",
            "-Djava.net.preferIPv4Stack=true",
            "-Dsun.net.inetaddr.ttl=0",
            "-Dsun.net.inetaddr.negative.ttl=0",
            "-Dtvbox.guarded.abi=${artifact.abi.configName}",
        )
        val webviewSerial = System.getProperty("tvbox.guarded.emulator.serial") ?: System.getenv("ANDROID_SERIAL")
        if (!webviewSerial.isNullOrBlank()) {
            command += "-Dtvbox.guarded.webview.adbSerial=$webviewSerial"
        }
        command += proxyProperties(spec.className)
        command += listOf(
            "-cp",
            System.getProperty("java.class.path"),
            HOST_MAIN,
            artifact.path.toAbsolutePath().toString(),
            spec.className,
            spec.providerKey,
            dataDirectory.toAbsolutePath().toString(),
        )
        if (artifact.nativeLibrary != null && artifact.guardData != null) {
            command += listOf(
                artifact.abi.configName,
                artifact.nativeLibrary.toAbsolutePath().toString(),
                artifact.guardData.toAbsolutePath().toString(),
            )
        }
        process = ProcessBuilder(command).start()
        logger.info("process start key=${spec.key} class=${spec.className} abi=${artifact.abi.configName} pid=${process.pid()}")
        input = process.outputWriter(StandardCharsets.UTF_8)
        output = process.inputReader(StandardCharsets.UTF_8)
        Thread({
            process.errorReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    synchronized(diagnostics) {
                        if (diagnostics.size == MAX_DIAGNOSTIC_LINES) diagnostics.removeFirst()
                        diagnostics.addLast(line.take(MAX_DIAGNOSTIC_LENGTH))
                    }
                }
            }
        }, "tvbox-guarded-catvod-errors").apply { isDaemon = true }.start()
        try {
            request(GuardedCatVodHostRequest("init", extension = spec.extension))
        } catch (failure: Throwable) {
            // This instance never becomes reachable to the owner when the constructor throws,
            // so nobody can call close(): kill the child JVM here or it survives the app.
            destroyQuietly()
            throw failure
        }
    }

    @Synchronized
    fun request(request: GuardedCatVodHostRequest): String {
        val started = System.nanoTime()
        val requestTag = "op=${request.operation} id=${tag(request.id)} query=${tag(request.query)} flag=${tag(request.flag)}"
        logger.info("request start $requestTag")
        check(process.isAlive) { "Guarded CatVod host is not running${diagnosticSuffix()}" }
        // Startup probes and earlier operations must not be reported as this request's failure.
        synchronized(diagnostics) { diagnostics.clear() }
        input.write(gson.toJson(request))
        input.newLine()
        input.flush()
        val future = readerExecutor.submit<String?> { output.readLine() }
        val line = try {
            future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (failure: Exception) {
            future.cancel(true)
            process.destroyForcibly()
            logger.log(Level.SEVERE, "request failed $requestTag error=timeout-or-stop${diagnosticSuffix()}", failure)
            throw IllegalArgumentException("Guarded CatVod host timed out or stopped${diagnosticSuffix()}", failure)
        } ?: throw IllegalArgumentException("Guarded CatVod host stopped without a response${diagnosticSuffix()}")
        val response = runCatching { gson.fromJson(line, GuardedCatVodHostResponse::class.java) }
            .getOrElse { throw IllegalArgumentException("Guarded CatVod host returned invalid JSON") }
        if (!response.ok) {
            logger.warning("request rejected $requestTag error=${response.error}${diagnosticSuffix()}")
            throw IllegalArgumentException(response.error ?: "Guarded CatVod operation failed")
        }
        return response.result.orEmpty().also { result ->
            logger.info(
                "request done $requestTag resultBytes=${result.toByteArray().size} resultSha=${tag(result)} " +
                    "elapsedMs=${(System.nanoTime() - started) / 1_000_000}",
            )
        }
    }

    override fun close() {
        if (process.isAlive) runCatching { request(GuardedCatVodHostRequest("close")) }
        destroyQuietly()
        logger.info("process closed pid=${process.pid()} exit=${runCatching { process.exitValue() }.getOrNull()}")
    }

    /** Releases streams, process and reader pool; safe to call from a failed constructor. */
    private fun destroyQuietly() {
        runCatching { input.close() }
        runCatching { output.close() }
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        readerExecutor.shutdownNow()
    }

    fun recentDiagnostics(): List<String> = synchronized(diagnostics) { diagnostics.toList() }

    private fun diagnosticSuffix(): String = synchronized(diagnostics) {
        diagnostics.toList().takeLast(12).filter(String::isNotBlank).joinToString(" | ")
            .takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
    }

    private fun safeSegment(value: String): String = value.map { character ->
        if (character.isLetterOrDigit() || character in "-_.") character else '_'
    }.joinToString("").take(80).ifBlank { "source" }

    private fun tag(value: String?): String {
        if (value.isNullOrEmpty()) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .take(6).joinToString("") { "%02x".format(it) }
        return "len=${value.length},sha=$digest"
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", true)

    private fun proxyProperties(providerClassName: String): List<String> {
        val environment = System.getenv()
        val result = mutableListOf<String>()
        fun add(scheme: String, configured: String?) {
            val value = configured?.trim().orEmpty()
            if (value.isEmpty()) return
            val uri = runCatching { URI(if ("://" in value) value else "http://$value") }.getOrNull() ?: return
            val host = uri.host ?: return
            val port = if (uri.port > 0) uri.port else 80
            result += "-D$scheme.proxyHost=$host"
            result += "-D$scheme.proxyPort=$port"
        }
        add("http", environment["HTTP_PROXY"] ?: environment["http_proxy"])
        add("https", environment["HTTPS_PROXY"] ?: environment["https_proxy"])
        val configured = (environment["NO_PROXY"] ?: environment["no_proxy"])
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            .orEmpty()
        val providerBypass = GuardedProviderNetworkPolicy.nonProxyHosts(providerClassName)
        (configured + providerBypass).distinct().takeIf(List<String>::isNotEmpty)?.let { values ->
            result += "-Dhttp.nonProxyHosts=${values.joinToString("|") { it.substringBefore(':') }}"
        }
        if (providerBypass.isNotEmpty()) {
            logger.info("provider network policy class=$providerClassName directHosts=${providerBypass.joinToString()}")
        }
        return result
    }

    private companion object {
        const val HOST_MAIN = "com.wuming.tv.guardhost.GuardedCatVodHostMain"
        const val REQUEST_TIMEOUT_SECONDS = 40L
        const val MAX_DIAGNOSTIC_LINES = 20
        const val MAX_DIAGNOSTIC_LENGTH = 1_000
    }
}
