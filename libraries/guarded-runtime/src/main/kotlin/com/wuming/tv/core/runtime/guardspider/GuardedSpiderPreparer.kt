package com.wuming.tv.core.runtime.guardspider

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.jar.JarFile

fun interface GuardedPayloadProducer {
    fun produce(abi: GuardedSpiderAbi, outputPayload: Path)
}

fun interface GuardedRuntimeAssetsProducer {
    fun produce(abi: GuardedSpiderAbi, outputLibrary: Path, outputGuard: Path)
}

data class PreparedGuardedSpiderArtifact(
    val path: Path,
    val abi: GuardedSpiderAbi,
    val packageSha256: String,
    val artifactSha256: String,
    val classCount: Int,
    val reused: Boolean,
    val nativeLibrary: Path? = null,
    val guardData: Path? = null,
)

class GuardedSpiderPreparer(
    private val packageReport: GuardedSpiderPackageReport,
    private val producer: GuardedPayloadProducer,
    private val runtimeAssetsProducer: GuardedRuntimeAssetsProducer? = null,
    private val converter: GuardedDexConverter = Dex2JarToolchain(),
    private val cacheRoot: Path = GuardedSpiderCachePaths.root().resolve("guarded-spiders"),
) {

    fun prepare(abi: GuardedSpiderAbi): PreparedGuardedSpiderArtifact {
        require(abi in packageReport.supportedAbis) { "Package does not contain ${abi.configName}" }
        val abiRoot = cacheRoot.resolve(packageReport.sha256).resolve(abi.configName)
        val entry = abiRoot.resolve(CACHE_FORMAT)
        Files.createDirectories(abiRoot)
        FileChannel.open(
            abiRoot.resolve("$CACHE_FORMAT.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                readValid(entry, abi, reused = true)?.let { return it }
                val staging = Files.createTempDirectory(abiRoot, "$CACHE_FORMAT-")
                try {
                    val payload = staging.resolve("decrypted.payload")
                    val artifact = staging.resolve(ARTIFACT_NAME)
                    producer.produce(abi, payload)
                    require(Files.isRegularFile(payload) && Files.size(payload) in 1..MAX_PAYLOAD_BYTES) {
                        "Guarded spider produced an invalid DEX payload"
                    }
                    converter.convert(payload, artifact)
                    runtimeAssetsProducer?.produce(
                        abi,
                        staging.resolve(NATIVE_LIBRARY_NAME),
                        staging.resolve(GUARD_DATA_NAME),
                    )
                    if (runtimeAssetsProducer != null) {
                        require(validRuntimeAsset(staging.resolve(NATIVE_LIBRARY_NAME))) {
                            "Guarded spider produced an invalid native library"
                        }
                        require(validRuntimeAsset(staging.resolve(GUARD_DATA_NAME))) {
                            "Guarded spider produced invalid guard data"
                        }
                    }
                    val jar = inspectJar(artifact)
                        ?: throw IllegalArgumentException("Converted guarded spider is not a class JAR")
                    writeMetadata(staging, abi, jar)
                    Files.deleteIfExists(payload)
                    deleteTree(entry)
                    moveReplace(staging, entry)
                } catch (failure: Throwable) {
                    deleteTree(staging)
                    throw failure
                }
                return readValid(entry, abi, reused = false)
                    ?: throw IllegalArgumentException("Prepared guarded spider cache failed validation")
            }
        }
    }

    private fun readValid(entry: Path, abi: GuardedSpiderAbi, reused: Boolean): PreparedGuardedSpiderArtifact? {
        val artifact = entry.resolve(ARTIFACT_NAME)
        val metadataPath = entry.resolve(METADATA_NAME)
        if (!Files.isRegularFile(artifact) || !Files.isRegularFile(metadataPath)) return null
        return runCatching {
            val metadata = Properties().apply { Files.newInputStream(metadataPath).use { load(it) } }
            require(metadata.getProperty("cacheFormat") == CACHE_FORMAT)
            require(metadata.getProperty("packageSha256") == packageReport.sha256)
            require(metadata.getProperty("abi") == abi.configName)
            require(metadata.getProperty("converterVersion") == Dex2JarToolchain.VERSION)
            val jar = inspectJar(artifact) ?: error("Invalid cached JAR")
            require(metadata.getProperty("artifactSha256") == jar.sha256)
            require(metadata.getProperty("classCount") == jar.classCount.toString())
            val hasRuntimeAssets = metadata.getProperty("hasRuntimeAssets").toBoolean()
            require(runtimeAssetsProducer == null || hasRuntimeAssets)
            val nativeLibrary = entry.resolve(NATIVE_LIBRARY_NAME).takeIf { hasRuntimeAssets }
            val guardData = entry.resolve(GUARD_DATA_NAME).takeIf { hasRuntimeAssets }
            if (hasRuntimeAssets) {
                require(Files.isRegularFile(nativeLibrary))
                require(Files.isRegularFile(guardData))
                require(metadata.getProperty("nativeLibrarySha256") == sha256(requireNotNull(nativeLibrary)))
                require(metadata.getProperty("guardDataSha256") == sha256(requireNotNull(guardData)))
            }
            PreparedGuardedSpiderArtifact(
                artifact,
                abi,
                packageReport.sha256,
                jar.sha256,
                jar.classCount,
                reused,
                nativeLibrary,
                guardData,
            )
        }.getOrNull()
    }

    private fun writeMetadata(directory: Path, abi: GuardedSpiderAbi, jar: JarReport) {
        val metadata = Properties().apply {
            setProperty("cacheFormat", CACHE_FORMAT)
            setProperty("packageSha256", packageReport.sha256)
            setProperty("abi", abi.configName)
            setProperty("converterVersion", Dex2JarToolchain.VERSION)
            setProperty("converterArchiveSha256", Dex2JarToolchain.ARCHIVE_SHA256)
            setProperty("artifactSha256", jar.sha256)
            setProperty("classCount", jar.classCount.toString())
            val nativeLibrary = directory.resolve(NATIVE_LIBRARY_NAME)
            val guardData = directory.resolve(GUARD_DATA_NAME)
            val hasRuntimeAssets = Files.isRegularFile(nativeLibrary) && Files.isRegularFile(guardData)
            setProperty("hasRuntimeAssets", hasRuntimeAssets.toString())
            if (hasRuntimeAssets) {
                setProperty("nativeLibrarySha256", sha256(nativeLibrary))
                setProperty("guardDataSha256", sha256(guardData))
            }
        }
        Files.newOutputStream(directory.resolve(METADATA_NAME)).use {
            metadata.store(it, "TVBox guarded spider preparation cache")
        }
    }

    private fun inspectJar(path: Path): JarReport? = runCatching {
        if (!Files.isRegularFile(path) || Files.size(path) !in 1..MAX_ARTIFACT_BYTES) return@runCatching null
        val classes = JarFile(path.toFile(), true).use { jar ->
            jar.entries().asSequence().count { !it.isDirectory && it.name.endsWith(".class") }
        }
        if (classes < 1) null else JarReport(sha256(path), classes)
    }.getOrNull()

    private fun validRuntimeAsset(path: Path): Boolean =
        Files.isRegularFile(path) && Files.size(path) in 1..MAX_RUNTIME_ASSET_BYTES

    private data class JarReport(val sha256: String, val classCount: Int)

    private companion object {
        const val CACHE_FORMAT = "prepared-v2-native-d2j-${Dex2JarToolchain.VERSION}"
        const val ARTIFACT_NAME = "spider.jar"
        const val NATIVE_LIBRARY_NAME = "guard.so"
        const val GUARD_DATA_NAME = "ftyshinidie.guard"
        const val METADATA_NAME = "metadata.properties"
        const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024
        const val MAX_ARTIFACT_BYTES = 64L * 1024 * 1024
        const val MAX_RUNTIME_ASSET_BYTES = 64L * 1024 * 1024
    }
}
