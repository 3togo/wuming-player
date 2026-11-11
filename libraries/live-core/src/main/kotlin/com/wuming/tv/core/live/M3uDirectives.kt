package com.wuming.tv.core.live

import java.net.URI
import java.io.File

/** Shared lexical rules; player directives and framework models belong to the hosts. */
object M3uDirectives {
    private val attribute = Regex("""(?:^|\s)([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""")

    @JvmStatic fun separator(line: String): Int {
        // Only an attribute value's opening quote starts quoting. Apostrophes and
        // query-string quotes inside an unquoted URL remain literal URL characters.
        val quotedValues = attribute.findAll(line).mapNotNull { match ->
            (match.groups[2] ?: match.groups[3])?.range
        }.toList()
        return line.indices.firstOrNull { index ->
            line[index] == ',' && quotedValues.none { index in it }
        } ?: -1
    }

    @JvmStatic fun attributes(line: String): Map<String, String> {
        val end = if (line.startsWith("#EXTINF:", true)) separator(line).takeIf { it >= 0 } ?: line.length else line.length
        return attribute.findAll(line.substring(0, end)).associate { match ->
            match.groupValues[1].lowercase() to match.groups.drop(2).first { it != null }!!.value.trim()
        }
    }

    @JvmStatic fun name(line: String): String {
        val comma = separator(line)
        return (if (comma >= 0) line.substring(comma + 1).trim() else "")
            .ifBlank { attributes(line)["tvg-name"].orEmpty() }
    }

    @JvmStatic fun epgUrls(line: String, base: String): List<String> = attributes(line).let { attrs ->
        listOf("tvg-url", "x-tvg-url", "url-tvg").flatMap { key ->
            attrs[key].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
                .map { resolve(base, it) }
        }.distinct()
    }

    /** Braces in EPG templates are substitutions, not URI syntax. */
    @JvmStatic fun resolve(base: String, value: String): String {
        if (base.isBlank() || value.isBlank()) return value
        return runCatching {
            // Shield existing escapes so literal %7B does not become a template brace.
            fun protect(text: String) = text.replace("%", "%25").replace("{", "%7B").replace("}", "%7D")
            val location = if (!Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(base)) File(base).absoluteFile.toURI().toString() else base
            URI(protect(location)).resolve(protect(value)).toString()
                .replace("%7B", "{", true).replace("%7D", "}", true).replace("%25", "%", true)
        }.getOrDefault(value)
    }
}
