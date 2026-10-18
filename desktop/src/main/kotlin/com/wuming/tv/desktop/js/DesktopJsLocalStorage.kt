package com.wuming.tv.desktop.js

import com.wuming.tv.core.base.AtomicFilePublisher
import com.wuming.tv.core.runtime.js.JsLocalStorage
import com.wuming.tv.core.base.Digests
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.file.Files
import java.nio.file.Path

/** Durable, quota-limited, per-provider CatVod state, separate from account credentials. */
class DesktopJsLocalStorage private constructor(
    private val file: Path,
    private val gson: Gson = Gson(),
) : JsLocalStorage {
    private val values = load()

    @Synchronized
    override fun get(namespace: String, key: String): String = values[id(namespace, key)].orEmpty()

    @Synchronized
    override fun set(namespace: String, key: String, value: String) {
        require(namespace.length <= MAX_NAMESPACE && key.length <= MAX_KEY) { "Provider storage key is too long" }
        require(value.toByteArray().size <= MAX_VALUE_BYTES) { "Provider storage value exceeds quota" }
        val id = id(namespace, key)
        require(id in values || values.size < MAX_ENTRIES) { "Provider storage entry quota exceeded" }
        val updated = LinkedHashMap(values).apply { put(id, value) }
        val json = gson.toJson(updated)
        require(json.toByteArray().size <= MAX_FILE_BYTES) { "Provider storage quota exceeded" }
        values.clear()
        values.putAll(updated)
        persist(json)
    }

    @Synchronized
    override fun delete(namespace: String, key: String) {
        if (values.remove(id(namespace, key)) != null) persist(gson.toJson(values))
    }

    private fun load(): LinkedHashMap<String, String> {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) return linkedMapOf()
        return runCatching {
            gson.fromJson<Map<String, String>>(Files.readString(file), object : TypeToken<Map<String, String>>() {}.type)
                .entries.take(MAX_ENTRIES).associateTo(LinkedHashMap()) { it.toPair() }
        }.getOrDefault(linkedMapOf())
    }

    private fun persist(json: String) {
        AtomicFilePublisher.publish(file, file.fileName.toString(), ownerOnly = true) {
            Files.writeString(it, json)
        }
        applyOwnerOnlyPermissions(file)
    }

    private fun id(namespace: String, key: String) = "$namespace\u0000$key"

    companion object {
        private const val MAX_NAMESPACE = 128
        private const val MAX_KEY = 512
        private const val MAX_ENTRIES = 512
        private const val MAX_VALUE_BYTES = 256 * 1024
        private const val MAX_FILE_BYTES = 1024 * 1024L

        fun openDefault(providerKey: String): DesktopJsLocalStorage = open(defaultRoot(), providerKey)

        fun open(root: Path, providerKey: String): DesktopJsLocalStorage {
            require(providerKey.isNotBlank()) { "Provider key is required" }
            return DesktopJsLocalStorage(root.resolve(hash(providerKey) + ".json"))
        }

        fun reconcileDefault(activeProviderKeys: Set<String>) = reconcile(defaultRoot(), activeProviderKeys)

        @Synchronized
        fun reconcile(root: Path, activeProviderKeys: Set<String>) {
            if (!Files.isDirectory(root)) return
            val activeFiles = activeProviderKeys.mapTo(hashSetOf()) { hash(it) + ".json" }
            Files.newDirectoryStream(root, "*.json").use { entries ->
                entries.filter { Files.isRegularFile(it) && it.fileName.toString() !in activeFiles }
                    .forEach(Files::deleteIfExists)
            }
        }

        private fun defaultRoot(): Path {
            val base = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), ".config")
            return base.resolve("tvbox-desktop/js-providers-v1")
        }

        private fun hash(value: String): String = Digests.sha256Hex(value)

        private fun applyOwnerOnlyPermissions(path: Path) = AtomicFilePublisher.ownerOnly(path)
    }
}
