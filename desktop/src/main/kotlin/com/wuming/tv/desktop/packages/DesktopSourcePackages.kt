package com.wuming.tv.desktop.packages

import com.wuming.tv.core.source.packages.FilePackageStorage
import com.wuming.tv.core.source.packages.SourcePackageLibrary
import com.wuming.tv.core.source.packages.SourcePackageInspector
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A private loopback bridge keeps local JS on the existing bounded HTTP host API. */
object DesktopSourcePackages {
    val library by lazy {
        val home = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")
        SourcePackageLibrary(FilePackageStorage(File(home, "wuming/source-packages"), restrictRootPermissions = true))
    }
    fun retainedJsStorageIdentities(installed: List<com.wuming.tv.core.source.packages.InstalledSourcePackage> = library.installed()): Set<String> = installed.flatMap { item ->
        item.current.enabledKeys.map { key ->
            com.wuming.tv.core.source.packages.PackageSourceIdentity.scoped(item.id, key) + "\u0000package://${item.id}/$key"
        }
    }.toSet()

    private val token = UUID.randomUUID().toString()
    private val directories = ConcurrentHashMap<String, File>()
    private val directoryIds = ConcurrentHashMap<String, String>()
    private val packageIds = ConcurrentHashMap<String, String>()
    private val packageCapabilities = ConcurrentHashMap<String, String>()
    private val configurations = ConcurrentHashMap.newKeySet<String>()
    private val server by lazy {
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newFixedThreadPool(4) { task -> Thread(task, "source-package-files").apply { isDaemon = true } }
            createContext("/$token/") { exchange ->
                try {
                    require(exchange.requestMethod in setOf("GET", "HEAD"))
                    val path = exchange.requestURI.path.removePrefix("/$token/")
                    val parts = path.split('/', limit = 4)
                    val bytes = when (parts.firstOrNull()) {
                        "config" -> library.configuration(packageCapabilities[parts[1]] ?: error("Package capability expired"), "$base/files/${parts[1]}").toByteArray()
                        "files" -> library.resource(packageCapabilities[parts[1]] ?: error("Package capability expired"), parts[2], parts[3])
                        "local" -> {
                            val root = directories[parts[1]] ?: error("Local package expired")
                            val relative = parts.drop(2).joinToString("/")
                            SourcePackageInspector.safePath(relative)
                            val file = File(root, relative).canonicalFile
                            require(file.path.startsWith(root.path + File.separator) && file.isFile)
                            require(file.length() <= SourcePackageInspector.MAX_FILE_BYTES)
                            val bytes = file.inputStream().use { it.readNBytes(SourcePackageInspector.MAX_FILE_BYTES + 1) }
                            require(bytes.size <= SourcePackageInspector.MAX_FILE_BYTES)
                            if ("${parts[1]}/$relative" in configurations) {
                                val decoded = com.wuming.tv.core.source.TvBoxConfigPayloadDecoder.decode(bytes)
                                val json = com.google.gson.JsonParser.parseString(decoded).asJsonObject
                                com.wuming.tv.core.source.config.ConfigResourceResolver.rewrite(json) { reference ->
                                    val suffixAt = listOf(";md5;", ";sha256;").map(reference::indexOf).filter { it >= 0 }.minOrNull() ?: reference.length
                                    val resource = reference.substring(0, suffixAt)
                                    val literal = File(file.parentFile, resource.substringBefore('?').substringBefore('#')).canonicalFile
                                    require(literal.path.startsWith(root.path + File.separator)) { "Resource escapes local package" }
                                    val names = if (literal.isFile) setOf(literal.relativeTo(root).invariantSeparatorsPath) else emptySet()
                                    val resolved = SourcePackageInspector.localPath(relative, resource, names)
                                    val encoded = URI(null, null, "/$resolved", null).toASCIIString()
                                    val tail = resource.drop(resource.substringBefore('?').substringBefore('#').length)
                                    "$base/local/${parts[1]}$encoded$tail${reference.substring(suffixAt)}"
                                }.toString().toByteArray()
                            } else bytes
                        }
                        else -> error("Unknown package resource")
                    }
                    exchange.responseHeaders.set("Cache-Control", "no-store")
                    exchange.responseHeaders.set("Content-Type", "application/octet-stream")
                    if (exchange.requestMethod == "HEAD") exchange.sendResponseHeaders(200, -1)
                    else { exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }
                } catch (_: Exception) { runCatching { exchange.sendResponseHeaders(404, -1) } }
                finally { exchange.close() }
            }
            val host = this
            Thread({ host.start() }, "source-package-start").apply { isDaemon = true; start(); join() }
        }
    }
    private val base: String get() = "http://127.0.0.1:${server.address.port}/$token"

    fun transport(location: String): String {
        if (location.startsWith("package://")) {
            val id = location.removePrefix("package://")
            require(id.matches(Regex("[a-f0-9]{32}")))
            val capability = packageIds.computeIfAbsent(id) { UUID.randomUUID().toString().also { token -> packageCapabilities[token] = id } }
            return "$base/config/$capability"
        }
        val uri = runCatching { URI(location) }.getOrNull()
        val file = when {
            uri?.scheme == "file" -> File(uri)
            uri?.scheme == null -> File(location)
            else -> return location
        }.absoluteFile.canonicalFile
        require(file.isFile) { "Local configuration does not exist" }
        val root = file.parentFile!!
        val key = directoryIds.computeIfAbsent(root.path) { UUID.randomUUID().toString() }
        directories[key] = root
        configurations.add("$key/${file.name}")
        return "$base/local/$key/" + URI(null, null, file.name, null).toASCIIString()
    }
}
