package com.wuming.tv.core.runtime.guardspider

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.jar.JarFile

/**
 * Prepares an **unguarded** Android spider package: a JAR whose only payload is `classes.dex`,
 * carrying neither `ftyshinidie.guard` nor an ARM guard library.
 *
 * Many configurations publish this shape (for example `spider: ./lib/fm.jar`). There is nothing to
 * decrypt, so the DEX goes straight through dex2jar and the provider runs in the same isolated
 * host as a guarded package, just without the native crypto bridge. The package is still
 * third-party code: callers keep pinning it by SHA-256 and gating activation on the desktop
 * provider allow-list.
 */
object PlainDexSpiderPreparer {

    fun prepare(
        packagePath: Path,
        expectedSha256: String,
        converter: GuardedDexConverter = Dex2JarToolchain(),
        cacheRoot: Path = GuardedSpiderCachePaths.root().resolve("plain-dex-spiders"),
    ): PreparedGuardedSpiderArtifact {
        require(Files.isRegularFile(packagePath)) { "Spider package is missing" }
        val packageSha256 = sha256(packagePath)
        require(packageSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
            "Spider package SHA-256 mismatch"
        }
        val root = cacheRoot.resolve(packageSha256)
        val entry = root.resolve(CACHE_FORMAT)
        Files.createDirectories(root)
        FileChannel.open(
            root.resolve("$CACHE_FORMAT.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                readValid(entry, packageSha256, reused = true)?.let { return it }
                val staging = Files.createTempDirectory(root, "$CACHE_FORMAT-")
                try {
                    val dex = extractDex(packagePath, staging.resolve(DEX_NAME))
                    val artifact = staging.resolve(ARTIFACT_NAME)
                    converter.convert(dex, artifact)
                    val jar = inspectJar(artifact)
                        ?: throw IllegalArgumentException("Converted spider is not a class JAR")
                    writeMetadata(staging, packageSha256, jar)
                    Files.deleteIfExists(dex)
                    deleteTree(entry)
                    moveReplace(staging, entry)
                } catch (failure: Throwable) {
                    deleteTree(staging)
                    throw failure
                }
                return readValid(entry, packageSha256, reused = false)
                    ?: throw IllegalArgumentException("Prepared spider cache failed validation")
            }
        }
    }

    /** Rejects anything that is not a single-DEX Android spider: guarded and portable jars differ. */
    private fun extractDex(packagePath: Path, target: Path): Path {
        var dex: String? = null
        JarFile(packagePath.toFile(), true).use { jar ->
            val entries = jar.entries().asSequence().filterNot { it.isDirectory }.toList()
            if (entries.size > MAX_ENTRIES) unsupported("Spider package contains too many entries")
            entries.forEach { entry ->
                val name = entry.name.substringAfterLast('/')
                when {
                    name.equals(DEX_NAME, ignoreCase = true) -> {
                        require(dex == null) { "Spider package contains duplicate DEX payloads" }
                        dex = entry.name
                    }
                    name.endsWith(".so", ignoreCase = true) -> unsupported("Spider package contains a native library")
                    name.equals(GUARD_NAME, ignoreCase = true) ->
                        unsupported("Spider package contains a guarded payload")
                    name.endsWith(".jar", ignoreCase = true) -> unsupported("Spider package nests another JAR")
                }
            }
        }
        val entry = dex ?: unsupported("Spider package has no $DEX_NAME")
        JarFile(packagePath.toFile(), true).use { jar ->
            jar.getInputStream(jar.getJarEntry(entry)).use { input ->
                Files.copy(input, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val size = Files.size(target)
        if (size !in 1..MAX_DEX_BYTES) unsupported("Spider package DEX has an invalid size")
        return target
    }

    private fun readValid(entry: Path, packageSha256: String, reused: Boolean): PreparedGuardedSpiderArtifact? {
        val artifact = entry.resolve(ARTIFACT_NAME)
        val metadataPath = entry.resolve(METADATA_NAME)
        if (!Files.isRegularFile(artifact) || !Files.isRegularFile(metadataPath)) return null
        return runCatching {
            val metadata = Properties().apply { Files.newInputStream(metadataPath).use { load(it) } }
            require(metadata.getProperty("cacheFormat") == CACHE_FORMAT)
            require(metadata.getProperty("packageSha256") == packageSha256)
            require(metadata.getProperty("converterVersion") == Dex2JarToolchain.VERSION)
            val jar = inspectJar(artifact) ?: error("Invalid cached JAR")
            require(metadata.getProperty("artifactSha256") == jar.sha256)
            require(metadata.getProperty("classCount") == jar.classCount.toString())
            PreparedGuardedSpiderArtifact(
                path = artifact,
                // There is no native code, so the ABI only names the cache and data directories.
                abi = GuardedSpiderAbi.ARMEABI_V7A,
                packageSha256 = packageSha256,
                artifactSha256 = jar.sha256,
                classCount = jar.classCount,
                reused = reused,
            )
        }.getOrNull()
    }

    private fun writeMetadata(directory: Path, packageSha256: String, jar: ArtifactReport) {
        val metadata = Properties().apply {
            setProperty("cacheFormat", CACHE_FORMAT)
            setProperty("packageSha256", packageSha256)
            setProperty("converterVersion", Dex2JarToolchain.VERSION)
            setProperty("converterArchiveSha256", Dex2JarToolchain.ARCHIVE_SHA256)
            setProperty("artifactSha256", jar.sha256)
            setProperty("classCount", jar.classCount.toString())
        }
        Files.newOutputStream(directory.resolve(METADATA_NAME)).use {
            metadata.store(it, "TVBox plain DEX spider preparation cache")
        }
    }

    private fun inspectJar(path: Path): ArtifactReport? = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) !in 1..MAX_ARTIFACT_BYTES) return@runCatching null
        val classes = JarFile(path.toFile(), true).use { jar ->
            jar.entries().asSequence().count { !it.isDirectory && it.name.endsWith(".class") }
        }
        if (classes < 1) null else ArtifactReport(sha256(path), classes)
    }.getOrNull()

    private fun unsupported(message: String): Nothing = throw IllegalArgumentException(message)

    private data class ArtifactReport(val sha256: String, val classCount: Int)

    private const val CACHE_FORMAT = "prepared-plain-d2j-${Dex2JarToolchain.VERSION}"
    private const val ARTIFACT_NAME = "spider.jar"
    private const val METADATA_NAME = "metadata.properties"
    private const val DEX_NAME = "classes.dex"
    private const val GUARD_NAME = "ftyshinidie.guard"
    private const val MAX_ENTRIES = 4_096
    private const val MAX_DEX_BYTES = 64L * 1024 * 1024
    private const val MAX_ARTIFACT_BYTES = 64L * 1024 * 1024
}
