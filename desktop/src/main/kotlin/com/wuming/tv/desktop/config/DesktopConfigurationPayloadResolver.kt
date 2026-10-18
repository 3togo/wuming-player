package com.wuming.tv.desktop.config

import com.wuming.tv.core.base.Digests
import com.wuming.tv.core.source.TvBoxConfigPayloadDecoder
import java.net.IDN
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.logging.Logger

/** Retrieves and decodes configuration envelopes; persistence and site mapping belong to the loader. */
internal class DesktopConfigurationPayloadResolver(private val loadResource: (String) -> ByteArray) {
    private val logger = Logger.getLogger("tvbox.config")

    fun load(input: String): ResolvedPayload {
        val primary = loadResource(input)
        logger.info("response attempt=1 bytes=${primary.size} sha256=${digest(primary)}")
        val first = resolvePayload(input, primary)
        return first.getOrElse { firstFailure ->
            logger.warning("decode attempt=1 failed error=${firstFailure.message}")
            val retry = runCatching { loadResource(input) }.getOrNull()
            if (retry != null && !retry.contentEquals(primary)) {
                logger.info("response attempt=2 bytes=${retry.size} sha256=${digest(retry)} changed=true")
                resolvePayload(input, retry).getOrElse { throw firstFailure }
            } else throw firstFailure
        }
    }

    private fun resolvePayload(input: String, primary: ByteArray): Result<ResolvedPayload> {
        val decoded = runCatching { TvBoxConfigPayloadDecoder.decode(primary) }
        if (decoded.isSuccess) return Result.success(ResolvedPayload(input, decoded.getOrThrow()))
        val fallback = advertisedFallbacks(input, primary).firstNotNullOfOrNull { fallback ->
            runCatching {
                ResolvedPayload(fallback, TvBoxConfigPayloadDecoder.decode(loadResource(fallback)))
            }.getOrNull()
        }
        return fallback?.let(Result.Companion::success) ?: Result.failure(decoded.exceptionOrNull()!!)
    }

    private fun advertisedFallbacks(input: String, payload: ByteArray): List<String> {
        val html = payload.toString(StandardCharsets.UTF_8)
        if (!html.contains("<html", ignoreCase = true)) return emptyList()
        val candidates = Regex("data-clipboard-text=[\"'](https?://[^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { it.groupValues[1].trim() }
            .toList()
        val requested = canonical(input) ?: return emptyList()
        val index = candidates.indexOfFirst { canonical(it) == requested }
        // Some configuration landing pages list mirrors but omit the landing URL itself.
        // Require a list in that case so an unrelated page with one clipboard link is ignored.
        if (index < 0 && candidates.size < 2) return emptyList()
        return candidates.drop(index + 1).filter { canonical(it) != requested }.take(MAX_ADVERTISED_FALLBACKS)
    }

    private fun canonical(value: String): String? = runCatching {
        val uri = URI(value)
        val host = uri.host ?: URL(value).host
        val asciiHost = IDN.toASCII(host).lowercase()
        "${uri.scheme.lowercase()}://$asciiHost${if (uri.port < 0) "" else ":${uri.port}"}${uri.path.orEmpty()}"
            .trimEnd('/')
    }.getOrNull()

    private fun digest(bytes: ByteArray): String = Digests.sha256Hex(bytes).take(16)

    private companion object {
        const val MAX_ADVERTISED_FALLBACKS = 3
    }
}
