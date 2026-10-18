package com.wuming.tv.core.source.packages

import com.google.gson.Gson
import com.wuming.tv.core.source.config.ConfigResourceResolver
import java.net.URI

/** Hosts provide private storage; publishIndex must atomically replace the previous index. */
interface SourcePackageStorage {
    fun readIndex(): String?
    fun publishIndex(json: String)
    fun writeVersion(id: String, version: String, files: Map<String, ByteArray>)
    fun readVersion(id: String, version: String): Map<String, ByteArray>
    fun readResource(id: String, version: String, path: String): ByteArray
}

data class InstalledSourcePackage(val id: String, val name: String, val current: PackageRevision, val previous: PackageRevision? = null)
data class PackageRevision(val version: String, val entry: String, val enabledKeys: List<String>)

class SourcePackageLibrary(private val storage: SourcePackageStorage) {
    private val gson = Gson()
    @Synchronized fun installed(): List<InstalledSourcePackage> = storage.readIndex()?.let {
        gson.fromJson(it, Array<InstalledSourcePackage>::class.java).toList()
    }.orEmpty()

    @Synchronized fun initialSelection(candidate: PackageCandidate): List<String> {
        val keys = candidate.sites.map { it.first }
        val previous = installed().firstOrNull { it.name == candidate.name }?.current?.enabledKeys
        return previous?.filter { it in keys } ?: keys.take(5)
    }

    /** Name is the explicit stable subscription identity; bytes identify its immutable version. */
    @Synchronized fun install(candidate: PackageCandidate, name: String, enabledKeys: List<String>): InstalledSourcePackage {
        require(candidate.errors.isEmpty()) { candidate.errors.joinToString("\n") }
        require(name.isNotBlank() && name.length <= 100 && name.none { it.isISOControl() }) { "Enter a package name (up to 100 characters)" }
        require(enabledKeys.isNotEmpty() && candidate.sites.map { it.first }.containsAll(enabledKeys)) { "Select at least one valid source" }
        val id = digest(name.trim().toByteArray()).take(32)
        val all = installed()
        val before = all.firstOrNull { it.id == id }
        val revision = PackageRevision(candidate.version, candidate.entry, enabledKeys.distinct())
        if (before?.current == revision) return before
        storage.writeVersion(id, revision.version, candidate.files)
        val result = InstalledSourcePackage(id, name.trim(), revision, before?.current)
        storage.publishIndex(gson.toJson(all.filterNot { it.id == id } + result))
        return result
    }

    @Synchronized fun rollback(id: String): InstalledSourcePackage {
        val all = installed()
        val item = all.first { it.id == id }
        val previous = requireNotNull(item.previous) { "No previous package version" }
        require(packageDigest(storage.readVersion(id, previous.version)) == previous.version) { "Previous package files changed" }
        val result = item.copy(current = previous, previous = item.current)
        storage.publishIndex(gson.toJson(all.map { if (it.id == id) result else it }))
        return result
    }

    @Synchronized fun configuration(id: String, resourceBase: String): String {
        val item = installed().first { it.id == id }
        val files = storage.readVersion(id, item.current.version)
        require(packageDigest(files) == item.current.version) { "Installed package files changed; import again" }
        val candidate = SourcePackageInspector.inspect(files).first { it.entry == item.current.entry }
        val root = ConfigResourceResolver.rewrite(candidate.config) { reference ->
            val suffixAt = listOf(";md5;", ";sha256;").map(reference::indexOf).filter { it >= 0 }.minOrNull() ?: reference.length
            val resource = reference.substring(0, suffixAt)
            val path = SourcePackageInspector.localPath(item.current.entry, resource, files.keys)
            val encoded = URI(null, null, "/$path", null).toASCIIString().removePrefix("/")
            val query = resource.substringAfter('?', "").substringBefore('#').let { if (resource.contains('?')) "?$it" else "" }
            val fragment = resource.substringAfter('#', "").let { if (resource.contains('#')) "#$it" else "" }
            "$resourceBase/${item.current.version}/$encoded$query$fragment${reference.substring(suffixAt)}"
        }
        // The loader scopes host identity AFTER decoding; provider contracts retain their raw keys.
        root.addProperty("wumingPackageId", id)
        val selected = com.google.gson.JsonArray()
        root.getAsJsonArray("sites").forEach { element ->
            if (element.asJsonObject.get("key").asString in item.current.enabledKeys) selected.add(element)
        }
        root.add("sites", selected)
        // HTTP/Python-only selections must not preload an unrelated root DEX binary on Android.
        if (selected.none {
            val site = it.asJsonObject
            site.get("type")?.asInt == 3 && !site.get("api")?.asString.orEmpty().substringBefore('?').endsWith(".py", true)
        }) root.remove("spider")
        return gson.toJson(root)
    }

    @Synchronized fun resource(id: String, version: String, path: String): ByteArray {
        require(id.matches(Regex("[a-f0-9]{32}")) && version.matches(Regex("[a-f0-9]{64}"))) { "Invalid package identity" }
        SourcePackageInspector.safePath(path)
        require(installed().any { it.id == id }) { "Package is not installed" }
        // Immutable older version URLs remain valid for in-flight players across repeated updates.
        return storage.readResource(id, version, path)
    }
}
