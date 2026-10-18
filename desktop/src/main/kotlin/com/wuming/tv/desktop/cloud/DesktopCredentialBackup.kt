package com.wuming.tv.desktop.cloud

import com.wuming.tv.core.cloud.CredentialBackupCodec
import com.wuming.tv.core.cloud.CredentialBackupFiles
import com.wuming.tv.core.cloud.LegacyCredentialBackupAction
import com.wuming.tv.core.runtime.cloudlogin.CloudCredentialStore
import com.wuming.tv.core.runtime.cloudlogin.desktopQrCloudProviders
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * User-initiated cloud-credential backup for the desktop client.
 *
 * Live credentials remain in the Secret Service (see [SecretServiceCredentialStore]); this only
 * reads them out into an encrypted export the user can move off-machine. The envelope is the shared
 * [CredentialBackupCodec] one, so desktop and Android backups are interoperable.
 *
 * The default directory is `~/.config/無名/credentials`. Earlier desktop builds used a
 * `tvbox-desktop` config namespace, and an intermediate proposal used `~/無名/credentials`, so
 * [migrate] relocates any credential backups found in those legacy directories into the new one,
 * following the shared rules in [CredentialBackupFiles].
 */
object DesktopCredentialBackup {

    /** The directory new credential backups are written to and imported from. */
    fun defaultDirectory(): Path {
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".config")
        return config.resolve("無名").resolve(CredentialBackupFiles.DIRECTORY_NAME)
    }

    /** Candidate directories a previous build may have used for credential backups. */
    private fun legacyDirectories(): List<Path> {
        val home = System.getProperty("user.home")
        val configRoots = listOfNotNull(
            System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of),
            Path.of(home, ".config"),
        )
        val roots = mutableListOf<Path>()
        for (root in configRoots) {
            roots.add(root.resolve("tvbox-desktop").resolve(CredentialBackupFiles.DIRECTORY_NAME))
            roots.add(root.resolve("tvbox-desktop"))
        }
        roots.add(Path.of(home, "無名").resolve(CredentialBackupFiles.DIRECTORY_NAME))
        roots.add(Path.of(home, "無名"))
        return roots
    }

    fun suggestFilename(): String = CredentialBackupFiles.suggestFilename()

    /**
     * Relocates credential backups from every legacy directory into [defaultDirectory]. A legacy
     * file is moved only when its name is still unique in the new directory; if a file with the same
     * name already exists there, the legacy copy is removed. Returns the number of files moved.
     * Best-effort: any single failure is reported through [onError] and skipped.
     */
    fun migrate(onError: (Path, Throwable) -> Unit = { _, _ -> }): Int {
        val target = defaultDirectory()
        if (!Files.isDirectory(target)) {
            runCatching { Files.createDirectories(target) }.onFailure { return 0 }
        }
        var moved = 0
        // A backup too small to be a valid copy is discarded wherever it lives: the target
        // directory and every legacy directory alike.
        purgeTooSmall(target, onError)
        for (legacy in legacyDirectories()) {
            if (legacy == target || !Files.isDirectory(legacy)) continue
            purgeTooSmall(legacy, onError)
            val files = runCatching { Files.list(legacy).use { it.toList() } }.getOrNull() ?: continue
            for (file in files) {
                if (!Files.isRegularFile(file)) continue
                if (!CredentialBackupFiles.isBackupFileName(file.fileName.toString())) continue
                try {
                    val dest = target.resolve(file.fileName.toString())
                    // Shared policy: relocate only while the name is still free in the target,
                    // otherwise discard the legacy copy rather than overwrite or rename it.
                    when (CredentialBackupFiles.relocation(Files.size(file), Files.exists(dest))) {
                        LegacyCredentialBackupAction.MOVE -> {
                            moveAtomic(file, dest)
                            moved++
                        }
                        LegacyCredentialBackupAction.DISCARD -> Files.deleteIfExists(file)
                    }
                } catch (error: Exception) {
                    onError(file, error)
                }
            }
        }
        return moved
    }

    private fun purgeTooSmall(dir: Path, onError: (Path, Throwable) -> Unit) {
        if (!Files.isDirectory(dir)) return
        val files = runCatching { Files.list(dir).use { it.toList() } }.getOrNull() ?: return
        for (file in files) {
            if (!Files.isRegularFile(file)) continue
            if (!CredentialBackupFiles.isBackupFileName(file.fileName.toString())) continue
            try {
                if (CredentialBackupFiles.isTooSmall(Files.size(file))) {
                    // Too small to be a valid backup: always discard the truncated or corrupt copy,
                    // regardless of which directory it lives in.
                    Files.deleteIfExists(file)
                }
            } catch (error: Exception) {
                onError(file, error)
            }
        }
    }

    /** Collects every stored cloud credential into an id->value map for export. */
    fun capture(store: CloudCredentialStore): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (id in desktopQrCloudProviders(store).map { it.id() }) {
            val value = store.read(id).trim()
            if (value.isNotEmpty()) result[id] = value
        }
        return result
    }

    @Throws(Exception::class)
    fun exportTo(store: CloudCredentialStore, passphrase: CharArray): Path {
        val credentials = capture(store)
        require(credentials.isNotEmpty()) { "No cloud credentials are currently stored to back up." }
        val encoded = CredentialBackupCodec.encrypt(credentials, passphrase)
        val dir = defaultDirectory()
        Files.createDirectories(dir)
        runCatching { Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------")) }
        val target = dir.resolve(suggestFilename())
        Files.write(target, encoded)
        return target
    }

    @Throws(Exception::class)
    fun importFrom(store: CloudCredentialStore, file: Path, passphrase: CharArray): Int {
        require(Files.isRegularFile(file)) { "Backup file not found" }
        val encoded = Files.readAllBytes(file)
        require(encoded.size <= CredentialBackupCodec.MAX_FILE_BYTES) { "Backup is too large" }
        val values = CredentialBackupCodec.decrypt(encoded, passphrase)
        val accounts = desktopAccounts(values)
        require(accounts.isNotEmpty()) { "Backup contains no supported desktop account credentials" }
        for ((id, value) in accounts) store.write(id, value)
        return accounts.size
    }

    /** Repair imports made by older desktop builds, which saved Android keys verbatim. */
    fun migratePreviouslyImportedAndroidKeys(store: CloudCredentialStore): Int {
        val legacyKeys = listOf(
            "vault:opendrive/alipan/token", "vault:opendrive/alipan/credential", "cookies",
        )
        val legacy = legacyKeys.mapNotNull { key ->
            store.read(key).takeIf(String::isNotBlank)?.let { key to it }
        }.toMap()
        var migrated = 0
        for ((id, value) in desktopAccounts(legacy)) {
            if (store.read(id).isBlank()) {
                store.write(id, value)
                migrated++
            }
        }
        return migrated
    }

    /** Android stores account tokens by vault reference and cookies by storage domain. */
    internal fun desktopAccounts(values: Map<String, String>, nowMillis: Long = System.currentTimeMillis()): Map<String, String> {
        val accounts = LinkedHashMap<String, String>()
        for (id in listOf("alipan", "baidu", "quark", "uc", "bilibili")) {
            values[id]?.takeIf(String::isNotBlank)?.let { accounts[id] = it }
        }
        if ("alipan" !in accounts) {
            sequenceOf("vault:opendrive/alipan/token", "vault:opendrive/alipan/credential")
                .mapNotNull(values::get).firstOrNull(String::isNotBlank)?.let { accounts["alipan"] = it }
        }
        val domains = mapOf(
            "baidu.com" to "baidu", "quark.cn" to "quark",
            "uc.cn" to "uc", "bilibili.com" to "bilibili",
        )
        val accountCookies = mapOf(
            "baidu" to setOf("BDUSS", "BDUSS_BFESS"),
            "quark" to setOf("__pus", "__puus"),
            "uc" to setOf("__pus", "__puus"),
            "bilibili" to setOf("SESSDATA"),
        )
        val cookies = runCatching { JsonParser.parseString(values["cookies"] ?: "{}").asJsonObject }.getOrNull()
        if (cookies != null) {
            val grouped = LinkedHashMap<String, LinkedHashMap<String, String>>()
            for ((key, raw) in cookies.entrySet()) {
                val domain = key.substringBefore('|')
                val name = key.substringAfter('|', "")
                val provider = domains[domain] ?: continue
                if (provider in accounts || name !in accountCookies.getValue(provider) || !raw.isJsonPrimitive) continue
                val parts = raw.asString.split(';')
                val pair = parts.firstOrNull()?.split('=', limit = 2) ?: continue
                if (pair.size != 2 || pair[0] != name || pair[1].isBlank()) continue
                val attributes = parts.drop(1).mapNotNull {
                    val item = it.split('=', limit = 2)
                    if (item.size == 2) item[0].trim() to item[1].trim() else null
                }.toMap()
                val cookieDomain = attributes["domain"]?.removePrefix(".") ?: continue
                if (cookieDomain != domain && !cookieDomain.endsWith(".$domain")) continue
                val expiry = attributes["expiresAt"]?.toLongOrNull() ?: continue
                if (expiry <= nowMillis) continue
                grouped.getOrPut(provider, ::LinkedHashMap)[name] = pair[1]
            }
            for ((provider, pairs) in grouped) {
                if (pairs.isNotEmpty()) accounts[provider] = pairs.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
        }
        return accounts
    }

    private fun moveAtomic(from: Path, to: Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
