package com.wuming.tv.core.runtime.guardspider

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

fun interface GuardedDexConverter {
    fun convert(input: Path, output: Path)
}

class Dex2JarToolchain(
    private val toolsRoot: Path = GuardedSpiderCachePaths.root().resolve("tools"),
) : GuardedDexConverter {

    override fun convert(input: Path, output: Path) {
        require(Files.isRegularFile(input)) { "DEX payload is missing" }
        val home = ensureInstalled()
        output.parent?.let(Files::createDirectories)
        val temporary = output.resolveSibling("${output.fileName}.tmp")
        Files.deleteIfExists(temporary)
        val errorLog = output.resolveSibling("${output.fileName}.dex2jar.log")
        val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
        val process = ProcessBuilder(
            java.toString(),
            "-Xms32m",
            "-Xmx768m",
            "-Dfile.encoding=UTF-8",
            "-cp",
            home.resolve("lib/*").toString(),
            "com.googlecode.dex2jar.tools.Dex2jarCmd",
            "-f",
            "-o",
            temporary.toString(),
            input.toString(),
        ).redirectError(errorLog.toFile()).redirectOutput(errorLog.toFile()).start()
        if (!process.waitFor(CONVERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            Files.deleteIfExists(temporary)
            throw IllegalArgumentException("DEX conversion timed out")
        }
        if (process.exitValue() != 0 || !isClassJar(temporary)) {
            Files.deleteIfExists(temporary)
            val diagnostic = runCatching { Files.readString(errorLog) }.getOrDefault("")
                .lineSequence().map(String::trim).lastOrNull(String::isNotEmpty).orEmpty().take(500)
            throw IllegalArgumentException("DEX conversion failed: $diagnostic")
        }
        moveReplace(temporary, output)
        Files.deleteIfExists(errorLog)
    }

    private fun ensureInstalled(): Path {
        Files.createDirectories(toolsRoot)
        val home = toolsRoot.resolve("dex-tools-$VERSION")
        val lockPath = toolsRoot.resolve("dex-tools-$VERSION.lock")
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                if (isInstallationValid(home)) return home
                val staging = Files.createTempDirectory(toolsRoot, "dex-tools-$VERSION-")
                try {
                    val archive = staging.resolve("download.zip")
                    download(archive)
                    if (sha256(archive) != ARCHIVE_SHA256) {
                        throw IllegalArgumentException("dex2jar download SHA-256 mismatch")
                    }
                    extractDistribution(archive, staging.resolve("content"))
                    Files.writeString(staging.resolve("content/.tvbox-sha256"), ARCHIVE_SHA256)
                    if (!isInstallationValid(staging.resolve("content"))) {
                        throw IllegalArgumentException("dex2jar distribution is incomplete")
                    }
                    deleteTree(home)
                    moveReplace(staging.resolve("content"), home)
                } finally {
                    deleteTree(staging)
                }
            }
        }
        return home
    }

    private fun download(target: Path) {
        System.getenv("TVBOX_DEX2JAR_ARCHIVE")?.trim()?.takeIf(String::isNotEmpty)?.let { local ->
            Files.copy(Path.of(local), target, StandardCopyOption.REPLACE_EXISTING)
            return
        }
        val uri = URI(DOWNLOAD_URL)
        val connection = uri.toURL().openConnection(environmentProxy(uri)) as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "TVBox-Desktop/0.1")
        connection.inputStream.use { input ->
            Files.newOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_ARCHIVE_BYTES) { "dex2jar download exceeds its size limit" }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun environmentProxy(target: URI): Proxy {
        val environment = System.getenv()
        val noProxy = environment["NO_PROXY"] ?: environment["no_proxy"] ?: ""
        val bypassed = noProxy.split(',').map(String::trim).filter(String::isNotEmpty).any { rule ->
            val host = target.host.orEmpty().lowercase()
            val normalized = rule.substringBefore(':').lowercase()
            normalized == "*" || host == normalized.removePrefix(".") ||
                (normalized.startsWith('.') && host.endsWith(normalized))
        }
        if (bypassed) return Proxy.NO_PROXY
        val configured = when (target.scheme.lowercase()) {
            "https" -> environment["HTTPS_PROXY"] ?: environment["https_proxy"]
                ?: environment["HTTP_PROXY"] ?: environment["http_proxy"]
            else -> environment["HTTP_PROXY"] ?: environment["http_proxy"]
        }?.trim().orEmpty()
        if (configured.isEmpty()) return Proxy.NO_PROXY
        val proxyUri = runCatching { URI(if ("://" in configured) configured else "http://$configured") }.getOrNull()
            ?: return Proxy.NO_PROXY
        val host = proxyUri.host ?: return Proxy.NO_PROXY
        val port = if (proxyUri.port > 0) proxyUri.port else 80
        return Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))
    }

    private fun extractDistribution(archive: Path, destination: Path) {
        Files.createDirectories(destination)
        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            var entries = 0
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "dex2jar archive contains too many entries" }
                val relative = entry.name.removePrefix("dex-tools-v$VERSION/").replace('\\', '/')
                val include = relative.startsWith("lib/") || relative in setOf("LICENSE.txt", "NOTICE.txt")
                if (!include || entry.isDirectory) continue
                val target = destination.resolve(relative).normalize()
                require(target.startsWith(destination)) { "Unsafe dex2jar archive entry" }
                target.parent?.let(Files::createDirectories)
                Files.newOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        require(entryBytes <= MAX_ENTRY_BYTES && total <= MAX_EXPANDED_BYTES) {
                            "dex2jar archive expands beyond its size limit"
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun isInstallationValid(home: Path): Boolean =
        home.resolve(".tvbox-sha256").readStringOrNull() == ARCHIVE_SHA256 &&
            Files.isRegularFile(home.resolve("lib/dex-tools-v$VERSION.jar")) &&
            Files.isRegularFile(home.resolve("LICENSE.txt"))

    private fun isClassJar(path: Path): Boolean = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) <= 0) return@runCatching false
        JarFile(path.toFile(), true).use { jar -> jar.entries().asSequence().any { it.name.endsWith(".class") } }
    }.getOrDefault(false)

    private fun Path.readStringOrNull(): String? = runCatching { Files.readString(this).trim() }.getOrNull()

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", true)

    companion object {
        const val VERSION = "2.4"
        const val ARCHIVE_SHA256 = "ee7c45eb3c1d2474a6145d8d447e651a736a22d9664b6d3d3be5a5a817dda23a"
        const val DOWNLOAD_URL = "https://github.com/pxb1988/dex2jar/releases/download/v2.4/dex-tools-v2.4.zip"
        private const val CONVERSION_TIMEOUT_SECONDS = 120L
        private const val MAX_ARCHIVE_BYTES = 24L * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 32L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
        private const val MAX_ENTRIES = 128
    }
}

object GuardedSpiderCachePaths {
    fun root(): Path {
        val xdg = System.getenv("XDG_CACHE_HOME")?.trim().orEmpty()
        return if (xdg.isNotEmpty()) Path.of(xdg).resolve("tvbox-desktop")
        else Path.of(System.getProperty("user.home"), ".cache", "tvbox-desktop")
    }
}

internal fun moveReplace(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

internal fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { path -> runCatching { Files.deleteIfExists(path) } }
    }
}
