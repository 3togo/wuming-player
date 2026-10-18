package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.source.SpiderLocation
import com.wuming.tv.desktop.net.DesktopResourceLoader
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class TrustedJvmSpiderArtifact private constructor(
    val path: Path,
    private val temporary: Boolean,
) : AutoCloseable {

    override fun close() {
        if (temporary) Files.deleteIfExists(path)
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 64 * 1024 * 1024

        fun resolve(advertisedLocation: String): TrustedJvmSpiderArtifact {
            val location = SpiderLocation.locationOf(advertisedLocation)
            require(location.isNotEmpty()) { "JVM spider artifact location is empty" }
            val uri = runCatching { URI(location) }.getOrNull()
            return when {
                uri?.scheme.equals("http", true) || uri?.scheme.equals("https", true) -> download(uri!!)
                uri?.scheme.equals("file", true) -> TrustedJvmSpiderArtifact(Path.of(uri), false)
                uri?.scheme.isNullOrBlank() -> TrustedJvmSpiderArtifact(Path.of(location).toAbsolutePath(), false)
                else -> throw IllegalArgumentException("JVM spider artifact must use file or HTTP(S)")
            }
        }

        private fun download(uri: URI): TrustedJvmSpiderArtifact {
            val temporary = Files.createTempFile("tvbox-jvm-spider-", ".jar")
            try {
                val bytes = DesktopResourceLoader(maximumBytes = MAX_DOWNLOAD_BYTES).load(uri.toString())
                Files.write(temporary, bytes)
                return TrustedJvmSpiderArtifact(temporary, true)
            } catch (failure: Throwable) {
                Files.deleteIfExists(temporary)
                throw failure
            }
        }
    }
}
