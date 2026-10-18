package com.wuming.tv.desktop.cloud

import com.wuming.tv.core.cloud.CloudAccountProvider
import com.wuming.tv.core.runtime.cloudlogin.CloudCredentialStore
import com.wuming.tv.core.runtime.cloudlogin.desktopQrCloudProviders
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DesktopCloudAccounts(
    val credentials: SecretServiceCredentialStore = SecretServiceCredentialStore(),
) {
    init {
        if (credentials.persistenceAvailable) {
            runCatching { DesktopCredentialBackup.migratePreviouslyImportedAndroidKeys(credentials) }
                .onFailure { java.util.logging.Logger.getLogger("tvbox.cloud.accounts")
                    .log(java.util.logging.Level.WARNING, "Could not migrate previously imported accounts", it) }
        }
    }

    val providers: List<CloudAccountProvider> = desktopQrCloudProviders(credentials)
}

/** Uses the current user's Linux Secret Service without placing credentials in application files. */
class SecretServiceCredentialStore(
    private val executable: Path? = findExecutable("secret-tool"),
) : CloudCredentialStore {
    private val session = ConcurrentHashMap<String, String>()
    private val loaded = ConcurrentHashMap.newKeySet<String>()

    val persistenceAvailable: Boolean get() = executable != null

    override fun read(providerId: String): String {
        session[providerId]?.let { return it }
        if (providerId in loaded) return ""
        val tool = executable ?: return "".also { loaded += providerId }
        val process = runCatching {
            ProcessBuilder(tool.toString(), "lookup", "service", SERVICE, "provider", providerId)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrNull() ?: return "".also { loaded += providerId }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "".also { loaded += providerId }
        }
        if (process.exitValue() != 0) return "".also { loaded += providerId }
        return process.inputStream.bufferedReader().use { it.readText() }.trim().takeIf {
            it.length <= MAX_CREDENTIAL_LENGTH
        }?.also { session[providerId] = it; loaded += providerId }.orEmpty()
            .also { loaded += providerId }
    }

    override fun write(providerId: String, credential: String) {
        require(credential.isNotBlank() && credential.length <= MAX_CREDENTIAL_LENGTH) {
            "Cloud credential is invalid"
        }
        val tool = executable ?: throw IOException(
            "Persistent cloud login requires Linux Secret Service (install libsecret-tools)",
        )
        val process = runCatching {
            ProcessBuilder(
                tool.toString(), "store", "--label=TVBox Desktop $providerId",
                "service", SERVICE, "provider", providerId,
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrElse { throw IOException("Could not open the user's Secret Service", it) }
        try {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(credential)
                writer.newLine()
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IOException("Timed out while saving the cloud login in the user's Secret Service")
            }
            if (process.exitValue() != 0) {
                throw IOException("The user's Secret Service rejected the cloud login")
            }
            com.wuming.tv.desktop.cache.DesktopCacheGeneration.value.incrementAndGet()
            session[providerId] = credential
            loaded += providerId
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            process.destroyForcibly()
            throw IOException("Could not save the cloud login in the user's Secret Service", error)
        }
    }

    override fun remove(providerId: String) {
        val tool = executable ?: throw IOException(
            "Persistent cloud login requires Linux Secret Service (install libsecret-tools)",
        )
        val process = runCatching {
            ProcessBuilder(
                tool.toString(), "clear", "service", SERVICE, "provider", providerId,
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrElse { throw IOException("Could not open the user's Secret Service", it) }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Timed out while removing the cloud login from the user's Secret Service")
        }
        if (process.exitValue() !in 0..1) {
            throw IOException("The user's Secret Service could not remove the cloud login")
        }
        com.wuming.tv.desktop.cache.DesktopCacheGeneration.value.incrementAndGet()
        session.remove(providerId)
        loaded += providerId
    }

    companion object {
        private const val SERVICE = "tvbox-desktop-cloud"
        private const val MAX_CREDENTIAL_LENGTH = 64 * 1024

        private fun findExecutable(name: String): Path? = System.getenv("PATH").orEmpty()
            .split(System.getProperty("path.separator"))
            .asSequence()
            .map { Path.of(it, name) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    }
}
