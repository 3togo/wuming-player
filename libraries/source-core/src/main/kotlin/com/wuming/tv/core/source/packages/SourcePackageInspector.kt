package com.wuming.tv.core.source.packages

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.wuming.tv.core.source.TvBoxConfigPayloadDecoder
import com.wuming.tv.core.source.config.ConfigResourceResolver
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

class PackageCandidate(
    val name: String,
    val entry: String,
    val files: Map<String, ByteArray>,
    val config: JsonObject,
    val errors: List<String>,
    val warnings: List<String>,
    val artifacts: Map<String, String>,
) {
    val sites: List<Pair<String, String>> get() = config.getAsJsonArray("sites")?.mapNotNull {
        if (!it.isJsonObject) null else it.asJsonObject.let { site ->
            site.get("key")?.asString?.let { key -> key to (site.get("name")?.asString ?: key) }
        }
    }.orEmpty()
    val version: String = packageDigest(files)
}

object SourcePackageInspector {
    const val MAX_FILES = 2048
    const val MAX_FILE_BYTES = 32 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 128 * 1024 * 1024

    fun archive(input: InputStream): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        var entries = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= MAX_FILES) { "Too many archive entries" }
                val name = safePath(entry.name.trimEnd('/'))
                if (entry.isDirectory) continue
                require(!files.containsKey(name)) { "Duplicate archive path: $name" }
                val body = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16384)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Import canceled")
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(body.size().toLong() + count <= MAX_FILE_BYTES && total <= MAX_TOTAL_BYTES) { "Package exceeds import size limit" }
                    body.write(buffer, 0, count)
                }
                files[name] = body.toByteArray()
            }
        }
        require(files.isNotEmpty()) { "Archive contains no files" }
        return files
    }

    fun inspect(files: Map<String, ByteArray>, defaultName: String = "Source package"): List<PackageCandidate> {
        require(files.size <= MAX_FILES && files.values.sumOf { it.size.toLong() } <= MAX_TOTAL_BYTES) { "Package exceeds import size limit" }
        files.forEach { (path, bytes) -> safePath(path); require(bytes.size <= MAX_FILE_BYTES) { "Package file exceeds size limit" } }
        val decoded = files.toSortedMap().mapNotNull { (path, bytes) ->
            if (path.substringAfterLast('/') == "origin.json" || bytes.size > 4 * 1024 * 1024) return@mapNotNull null
            if (!path.endsWith(".json", true) && !path.endsWith(".txt", true)) return@mapNotNull null
            val config = runCatching { JsonParser.parseString(TvBoxConfigPayloadDecoder.decode(bytes)).asJsonObject }.getOrNull() ?: return@mapNotNull null
            if (config.get("sites")?.isJsonArray != true) return@mapNotNull null
            path to config
        }
        return decoded.map { (entryPath, config) ->
            val prefix = entryPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val local = files.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
            val entry = entryPath.removePrefix(prefix)
            val name = prefix.trimEnd('/').ifBlank { defaultName }
            inspectOne(name, entry, local, config)
        }
    }

    private fun inspectOne(name: String, entry: String, files: Map<String, ByteArray>, config: JsonObject): PackageCandidate {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val artifacts = linkedMapOf<String, String>()
        ConfigResourceResolver.rewrite(config) { ref ->
            val resource = ref.substringBefore(";md5;").substringBefore(";sha256;")
            val path = runCatching { localPath(entry, resource, files.keys) }.getOrNull()
            val bytes = path?.let(files::get)
            if (bytes == null) errors += "Missing or escaping local resource: ${resource.substringBefore('?').substringBefore('#').take(160)}"
            else {
                val algorithm = when { ref.contains(";md5;") -> "MD5"; ref.contains(";sha256;") -> "SHA-256"; else -> null }
                if (algorithm != null) {
                    val expected = ref.substringAfter(if (algorithm == "MD5") ";md5;" else ";sha256;")
                    if (expected.startsWith("http")) warnings += "Remote checksum not fetched: $path"
                    else if (!digest(bytes, algorithm).equals(expected, true)) errors += "Checksum mismatch: $path"
                }
            }
            ref
        }
        files.forEach { (path, bytes) ->
            if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
                val members = runCatching { archive(bytes.inputStream()).keys }.getOrElse {
                    errors += "Unreadable or oversized plugin archive: $path"; emptySet()
                }
                if (members.any { it.endsWith(".dex") }) {
                    artifacts[path] = digest(bytes)
                    when {
                        members.any { it.endsWith("ftyshinidie.guard") } && members.any { it.endsWith("ftyguard_v7.so") || it.endsWith("ftyguard_v8.so") } -> warnings += "Guarded DEX: $path — desktop requires exact artifact/ABI verification"
                        members.any { it.endsWith(".so") || it.endsWith(".guard") || it.endsWith("woshinidie.bin") } -> warnings += "Unsupported desktop guard/payload: $path"
                        else -> warnings += "Android DEX: $path — desktop provider compatibility unverified"
                    }
                }
            }
        }
        if (config.getAsJsonArray("sites").size() == 0) errors += "Package has no VOD sources; use the existing live-playlist importer instead"
        val keys = mutableSetOf<String>()
        config.getAsJsonArray("sites").forEach { element ->
            val site = element.takeIf { it.isJsonObject }?.asJsonObject
            val key = runCatching { site?.get("key")?.asString }.getOrNull().orEmpty()
            if (key.isBlank() || key != key.trim() || key.any { it.isISOControl() } || !keys.add(key)) errors += "Invalid or duplicate site key: ${key.take(100)}"
            val api = runCatching { site?.get("api")?.asString }.getOrNull().orEmpty()
            if (api.substringBefore('?').endsWith(".py", true)) warnings += "Python source: $key — requires the bundled native CPython runtime and compatible Python dependencies; arbitrary Java interop is not supported"
        }
        warnings += "Local files do not imply offline playback. Provider services and nested script dependencies remain unverified."
        return PackageCandidate(name, entry, files, config, errors.distinct(), warnings.distinct(), artifacts)
    }

    /** Literal percent filenames win when present; otherwise use normal URI decoding once. */
    fun localPath(entry: String, reference: String, names: Set<String>): String {
        val path = reference.substringBefore('?').substringBefore('#')
        val joined = entry.substringBeforeLast('/', "").let { if (it.isEmpty()) path else "$it/$path" }
        fun normalize(value: String): String {
            val stack = mutableListOf<String>()
            value.split('/').forEach { segment -> when (segment) {
                "", "." -> Unit
                ".." -> { require(stack.isNotEmpty()) { "Resource escapes package" }; stack.removeAt(stack.lastIndex) }
                else -> stack += segment
            } }
            return safePath(stack.joinToString("/"))
        }
        val literal = normalize(joined)
        if (literal in names) return literal
        return normalize(URI(joined.replace(" ", "%20")).path)
    }

    fun safePath(value: String): String {
        require(value.isNotEmpty() && value.length <= 1024 && !value.startsWith('/') && !value.contains('\\') && !value.contains(':') && value.none { it.isISOControl() }) { "Invalid package path" }
        require(value.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid package path" }
        return value
    }
}

internal fun digest(bytes: ByteArray, algorithm: String = "SHA-256"): String = MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun packageDigest(files: Map<String, ByteArray>): String = digest(files.toSortedMap().entries.joinToString("\n") { (path, bytes) -> "$path:${digest(bytes)}" }.toByteArray())
