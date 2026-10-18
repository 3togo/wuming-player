package com.wuming.tv.core.runtime.guardspider

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

/** Creates an explicitly diagnostic artifact; normal prepared JARs are never modified. */
class GuardedSpiderDiagnosticInstrumenter(
    private val cacheRoot: Path = GuardedSpiderCachePaths.root().resolve("diagnostic-spiders"),
) {
    fun instrument(
        source: PreparedGuardedSpiderArtifact,
        providerClassName: String,
    ): PreparedGuardedSpiderArtifact {
        require(providerClassName.matches(Regex("com\\.github\\.catvod\\.spider\\.[A-Za-z0-9_$]+"))) {
            "Diagnostic provider class name is invalid"
        }
        val providerSegment = providerClassName.substringAfterLast('.')
        val root = cacheRoot.resolve(source.artifactSha256).resolve(providerSegment)
        val entry = root.resolve(CACHE_FORMAT)
        Files.createDirectories(root)
        FileChannel.open(
            root.resolve("$CACHE_FORMAT.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.lock().use {
                readValid(entry, source, providerClassName, true)?.let { return it }
                val staging = Files.createTempDirectory(root, "$CACHE_FORMAT-")
                try {
                    val output = staging.resolve(ARTIFACT_NAME)
                    transform(source.path, output, providerClassName)
                    writeMetadata(staging, source, providerClassName, sha256(output))
                    deleteTree(entry)
                    moveReplace(staging, entry)
                } catch (failure: Throwable) {
                    deleteTree(staging)
                    throw failure
                }
                return readValid(entry, source, providerClassName, false)
                    ?: throw IllegalArgumentException("Diagnostic guarded spider cache failed validation")
            }
        }
    }

    private fun transform(source: Path, output: Path, providerClassName: String) {
        val targetEntry = providerClassName.replace('.', '/') + ".class"
        var transformed = false
        var entries = 0
        var totalBytes = 0L
        JarFile(source.toFile(), true).use { input ->
            JarOutputStream(Files.newOutputStream(output)).use { jar ->
                input.entries().asSequence().forEach { original ->
                    entries++
                    require(entries <= MAX_ENTRIES) { "Prepared JAR contains too many entries" }
                    if (original.name == MARKER_NAME) return@forEach
                    val bytes = if (original.isDirectory) ByteArray(0) else input.getInputStream(original).use { it.readAllBytes() }
                    totalBytes += bytes.size
                    require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "Prepared JAR exceeds diagnostic size limit" }
                    val content = if (original.name == targetEntry) {
                        instrumentClass(bytes).also { transformed = true }
                    } else bytes
                    jar.putNextEntry(JarEntry(original.name).apply { time = 0L })
                    if (!original.isDirectory) jar.write(content)
                    jar.closeEntry()
                }
                require(transformed) { "Provider class is missing from prepared JAR: $providerClassName" }
                jar.putNextEntry(JarEntry(MARKER_NAME).apply { time = 0L })
                jar.write("format=$CACHE_FORMAT\nprovider=$providerClassName\n".toByteArray())
                jar.closeEntry()
            }
        }
    }

    private fun instrumentClass(bytes: ByteArray): ByteArray {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        val detail = node.methods.firstOrNull {
            it.name == "detailContent" && it.desc == "(Ljava/util/List;)Ljava/lang/String;"
        } ?: throw IllegalArgumentException("Provider does not contain the CatVod detailContent contract")
        val injectedHandlers = instrumentExceptionHandlers(detail)
        val search = node.methods.firstOrNull {
            it.name == "searchContent" && it.desc == "(Ljava/lang/String;Z)Ljava/lang/String;"
        }
        val injectedArrays = search?.let(::instrumentArrayReads) ?: 0
        require(injectedHandlers + injectedArrays > 0) { "No diagnostic probes were injected" }
        return ClassWriter(ClassWriter.COMPUTE_MAXS).also(node::accept).toByteArray()
    }

    private fun instrumentExceptionHandlers(method: org.objectweb.asm.tree.MethodNode): Int {
        val handlers = method.tryCatchBlocks.filter { it.type == "java/lang/Exception" }.map { it.handler }.distinct()
        require(handlers.isNotEmpty()) { "Provider detailContent does not swallow Exception handlers" }
        var injected = 0
        handlers.forEach { handler ->
            val store = firstStore(handler)
                ?: throw IllegalArgumentException("Unable to identify swallowed detail exception variable")
            method.instructions.insert(store, InsnList().apply {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    DIAGNOSTIC_BRIDGE,
                    "reportProviderState",
                    "(Ljava/lang/Object;)V",
                    false,
                ))
                add(VarInsnNode(Opcodes.ALOAD, store.`var`))
                add(MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    DIAGNOSTIC_BRIDGE,
                    "report",
                    "(Ljava/lang/Throwable;)V",
                    false,
                ))
            })
            injected++
        }
        return injected
    }

    private fun instrumentArrayReads(method: org.objectweb.asm.tree.MethodNode): Int {
        var injected = 0
        method.instructions.toArray().filter { it.opcode == Opcodes.AALOAD }.forEach { read ->
            val index = previousCode(read)
            val array = previousCode(index)
            if (array is VarInsnNode && array.opcode == Opcodes.ALOAD) {
                method.instructions.insertBefore(array, InsnList().apply {
                    add(LdcInsnNode("search-array-local-${array.`var`}"))
                    add(VarInsnNode(Opcodes.ALOAD, array.`var`))
                    add(MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        DIAGNOSTIC_BRIDGE,
                        "reportValue",
                        "(Ljava/lang/String;Ljava/lang/Object;)V",
                        false,
                    ))
                })
                injected++
            }
        }
        return injected
    }

    private fun previousCode(node: AbstractInsnNode?): AbstractInsnNode? {
        var current = node?.previous
        while (current != null && current.opcode < 0) current = current.previous
        return current
    }

    private fun firstStore(handler: AbstractInsnNode): VarInsnNode? {
        var current = handler.next
        repeat(MAX_HANDLER_SCAN) {
            if (current is VarInsnNode && current.opcode == Opcodes.ASTORE) return current
            current = current?.next
        }
        return null
    }

    private fun readValid(
        entry: Path,
        source: PreparedGuardedSpiderArtifact,
        providerClassName: String,
        reused: Boolean,
    ): PreparedGuardedSpiderArtifact? = runCatching {
        val artifact = entry.resolve(ARTIFACT_NAME)
        val metadataPath = entry.resolve(METADATA_NAME)
        if (!Files.isRegularFile(artifact) || !Files.isRegularFile(metadataPath)) return@runCatching null
        val metadata = Properties().apply { Files.newInputStream(metadataPath).use(::load) }
        require(metadata.getProperty("cacheFormat") == CACHE_FORMAT)
        require(metadata.getProperty("sourceArtifactSha256") == source.artifactSha256)
        require(metadata.getProperty("providerClassName") == providerClassName)
        val transformedSha = sha256(artifact)
        require(metadata.getProperty("artifactSha256") == transformedSha)
        JarFile(artifact.toFile(), true).use { jar -> require(jar.getEntry(MARKER_NAME) != null) }
        PreparedGuardedSpiderArtifact(
            artifact,
            source.abi,
            source.packageSha256,
            transformedSha,
            source.classCount,
            reused,
            source.nativeLibrary,
            source.guardData,
        )
    }.getOrNull()

    private fun writeMetadata(
        directory: Path,
        source: PreparedGuardedSpiderArtifact,
        providerClassName: String,
        artifactSha256: String,
    ) {
        val metadata = Properties().apply {
            setProperty("cacheFormat", CACHE_FORMAT)
            setProperty("sourceArtifactSha256", source.artifactSha256)
            setProperty("packageSha256", source.packageSha256)
            setProperty("abi", source.abi.configName)
            setProperty("providerClassName", providerClassName)
            setProperty("artifactSha256", artifactSha256)
        }
        Files.newOutputStream(directory.resolve(METADATA_NAME)).use {
            metadata.store(it, "TVBox diagnostic guarded spider cache")
        }
    }

    private companion object {
        const val CACHE_FORMAT = "diagnostic-v3-asm-9.9"
        const val ARTIFACT_NAME = "spider-diagnostic.jar"
        const val METADATA_NAME = "metadata.properties"
        const val MARKER_NAME = "META-INF/tvbox-guarded-diagnostic.properties"
        const val DIAGNOSTIC_BRIDGE = "com/fongmi/tv/guardhost/GuardedProviderDiagnostics"
        const val MAX_HANDLER_SCAN = 12
        const val MAX_ENTRIES = 20_000
        const val MAX_UNCOMPRESSED_BYTES = 128L * 1024 * 1024
    }
}
