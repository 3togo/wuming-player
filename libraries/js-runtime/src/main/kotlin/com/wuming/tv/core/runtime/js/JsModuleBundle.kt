package com.wuming.tv.core.runtime.js

import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal class JsModuleBundle private constructor(val directory: Path, val entry: Path) : AutoCloseable {

    override fun close() {
        Files.walk(directory).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }

    companion object {
        private val environmentProxy = EnvironmentProxySelector()
        private const val MAX_MODULES = 32
        private const val MAX_MODULE_BYTES = 2 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
        private const val PARSER_GLOBALS = "assets://js/lib/parser-globals.js"
        private val imports = Regex("(?m)(\\b(?:import|export)\\s*(?:[^'\\\"]*?\\s*from\\s*)?|\\bimport\\s*)['\\\"]([^'\\\"]+)['\\\"]")
        private val BARE_SPECIFIER = Regex("[A-Za-z0-9_\\u00A0-\\uFFFF][^\\s]*\\.(?:m?js|json)(?:\\?[^\\s]*)?")

        fun fetch(entryUrl: String, moduleLoader: ((String) -> ByteArray)? = null): JsModuleBundle {
            val canonicalEntry = validated(entryUrl).toString()
            val root = Files.createTempDirectory("tvbox-js-modules-")
            val sources = linkedMapOf<String, String>()
            var total = 0

            fun load(location: String): String {
                sources[location]?.let { return it }
                require(sources.size < MAX_MODULES) { "JavaScript module graph is too large" }
                val bytes = when {
                    location.startsWith("assets://") -> {
                        val path = location.removePrefix("assets://").removePrefix("/")
                        require(path.startsWith("js/lib/")) { "Unsupported asset import" }
                        JsModuleBundle::class.java.classLoader.getResourceAsStream(path)?.use { it.readNBytes(MAX_MODULE_BYTES + 1) }
                            ?: throw IllegalArgumentException("Missing JavaScript asset: $path")
                    }
                    else -> moduleLoader?.invoke(location) ?: download(location)
                }
                require(bytes.size <= MAX_MODULE_BYTES) { "JavaScript module exceeds size limit" }
                total += bytes.size
                require(total <= MAX_TOTAL_BYTES) { "JavaScript module graph exceeds size limit" }
                val text = bytes.toString(StandardCharsets.UTF_8)
                sources[location] = text
                imports.findAll(text).map { it.groupValues[2] }.filter(::supportedSpecifier)
                    .forEach { load(resolve(location, it)) }
                return text
            }

            try {
                load(canonicalEntry)
                load(PARSER_GLOBALS)
                val names = sources.keys.associateWith { location -> hash(location) + ".mjs" }
                sources.forEach { (location, source) ->
                    var rewritten = imports.replace(source) { match ->
                        if (!supportedSpecifier(match.groupValues[2])) return@replace match.value
                        val target = resolve(location, match.groupValues[2])
                        "${match.groupValues[1]}'./${names.getValue(target)}'"
                    }
                    if (location == canonicalEntry) {
                        rewritten = "import './${names.getValue(PARSER_GLOBALS)}';\n$rewritten"
                    }
                    Files.writeString(root.resolve(names.getValue(location)), rewritten)
                }
                return JsModuleBundle(root, root.resolve(names.getValue(canonicalEntry)))
            } catch (failure: Throwable) {
                runCatching { Files.walk(root).use { it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) } }
                throw failure
            }
        }

        private fun resolve(parent: String, child: String): String = when {
            child.startsWith("assets://") -> child
            child.startsWith("http://") || child.startsWith("https://") -> validated(child).toString()
            parent.startsWith("assets://") -> {
                val base = URI(parent.replace("assets://", "https://assets.invalid/"))
                "assets://" + base.resolve(child).path.removePrefix("/")
            }
            else -> relativeHttp(parent, child)
        }

        private fun unwrapMirror(value: String): String {
            val uri = validated(value)
            val embedded = uri.rawPath.removePrefix("/")
            return if (embedded.startsWith("http://") || embedded.startsWith("https://")) {
                validated(embedded + (uri.rawQuery?.let { "?$it" } ?: "")).toString()
            } else uri.toString()
        }

        /**
         * DRPY/T4 modules import their libraries with bare sibling specifiers
         * (`import cheerio from "cheerio.min.js"`). Android's loader treats them as relative to
         * the importing module, so they must be fetched and rewritten like `./`-prefixed ones.
         * Absolute paths and anything carrying a scheme stay unsupported.
         */
        private fun supportedSpecifier(value: String): Boolean = when {
            value.startsWith("./") || value.startsWith("../") -> true
            value.startsWith("assets://") -> true
            value.startsWith("http://") || value.startsWith("https://") -> true
            value.startsWith("/") -> false
            value.contains("://") -> false
            else -> BARE_SPECIFIER.matches(value)
        }

        private fun relativeHttp(parent: String, child: String): String {
            var base = parent.substringBeforeLast('/', "") + "/"
            var remainder = child
            while (remainder.startsWith("../")) {
                base = base.dropLast(1).substringBeforeLast('/', "") + "/"
                remainder = remainder.removePrefix("../")
            }
            return validated(base + remainder.removePrefix("./")).toString()
        }

        private fun validated(value: String): URI = URI(value).also {
            require(it.scheme == "http" || it.scheme == "https") { "JavaScript modules must use HTTP(S)" }
            require(it.host != null && it.userInfo == null) { "Invalid JavaScript module URL" }
        }

        private fun download(location: String): ByteArray {
            val direct = unwrapMirror(location)
            // Mirror wrappers are often the slowest and least reliable hop. Prefer the embedded
            // canonical URL, while retaining the advertised mirror as a fallback.
            val candidates = if (direct == location) listOf(location) else listOf(direct, location)
            var failure: Throwable? = null
            candidates.forEach { candidate ->
                try {
                    val uri = URI(validated(candidate).toASCIIString())
                    val connection = uri.toURL().openConnection(environmentProxy.proxyFor(uri)) as HttpURLConnection
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "TVBox-Desktop/0.1")
                    val code = connection.responseCode
                    if (code !in 200..299) throw java.io.IOException("JavaScript module returned HTTP $code")
                    return connection.inputStream.use { it.readNBytes(MAX_MODULE_BYTES + 1) }
                } catch (error: Throwable) {
                    failure = error
                }
            }
            throw failure ?: java.io.IOException("Unable to download JavaScript module")
        }

        private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    }
}
