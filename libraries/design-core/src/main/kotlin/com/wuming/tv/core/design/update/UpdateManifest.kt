package com.wuming.tv.core.design.update

import com.google.gson.JsonParser
import java.net.URI

/** Shared update selection policy; HTTP, package inspection and installation are platform ports. */
data class UpdateArtifact(
    val code: Int, val name: String, val description: String,
    val url: String, val sha256: String, val certificateSha256: String,
    val packageName: String,
    val urls: List<String> = listOf(url),
)

object UpdateManifest {
    @JvmStatic
    fun select(json: String, repository: String, channel: String, mode: String, abi: String,
               packageName: String, installedCode: Long): UpdateArtifact? {
        require(channel == "debug" || channel == "release")
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: throw IllegalArgumentException("Invalid update manifest JSON")
        require(root["schema"]?.asInt == 1 && root["channel"]?.asString == channel)
        val code = root["code"]?.asInt ?: throw IllegalArgumentException("Missing version code")
        require(code > 0) { "Invalid update version: $code" }
        if (code <= installedCode) return null
        val artifacts = root["artifacts"]?.asJsonArray ?: throw IllegalArgumentException("Missing artifacts array")
        val asset = artifacts.mapNotNull { runCatching { it.asJsonObject }.getOrNull() }.singleOrNull {
            it["mode"]?.asString == mode && it["abi"]?.asString == abi
        } ?: throw IllegalArgumentException("No matching artifact for mode=$mode abi=$abi")
        require(asset["packageName"]?.asString == packageName)
        val url = asset["url"]?.asString ?: throw IllegalArgumentException("Missing artifact URL")
        val urls = (listOf(url) + (asset["mirrors"]?.asJsonArray?.mapNotNull { runCatching { it.asString }.getOrNull() } ?: emptyList())).distinct()
        require(urls.size <= 2) { "Too many update mirrors" }
        urls.forEach { validateUrl(it, repository) }
        val digest = asset["sha256"]?.asString ?: throw IllegalArgumentException("Missing sha256")
        val certificate = asset["certificateSha256"]?.asString ?: throw IllegalArgumentException("Missing certificateSha256")
        require(digest.matches(Regex("[0-9a-f]{64}")) && certificate.matches(Regex("[0-9a-f]{64}")))
        return UpdateArtifact(code, root["name"]?.asString ?: "", root["desc"]?.asString ?: "",
            url, digest, certificate, packageName, urls)
    }

    /** Results are supplied in response order. A lagging mirror must not mask a newer build. */
    @JvmStatic
    fun newest(artifacts: List<UpdateArtifact>, preferredHost: String = ""): UpdateArtifact? {
        val code = artifacts.maxOfOrNull { it.code } ?: return null
        val newest = artifacts.filter { it.code == code }
        val first = newest.first()
        require(newest.all { it.sha256 == first.sha256 && it.certificateSha256 == first.certificateSha256 &&
            it.packageName == first.packageName }) { "Update mirrors disagree about version $code" }
        val urls = newest.flatMap { it.urls }.distinct().sortedBy { if (URI(it).host == preferredHost) 0 else 1 }
        return first.copy(url = urls.first(), urls = urls)
    }

    private fun validateUrl(url: String, repository: String) {
        val uri = URI(url)
        val trustedRepository = when (uri.host) {
            "github.com" -> repository
            "gitee.com" -> if (repository == "3togo/TV-updates") "freetogo/TV-updates" else null
            else -> null
        }
        require(trustedRepository != null && uri.scheme == "https" && uri.port == -1 &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.startsWith("/$trustedRepository/releases/download/") &&
            uri.rawPath.endsWith(".apk") && !uri.rawPath.contains("..") && !uri.rawPath.contains('%')) {
            "Untrusted update download URL: $url"
        }
    }

}
