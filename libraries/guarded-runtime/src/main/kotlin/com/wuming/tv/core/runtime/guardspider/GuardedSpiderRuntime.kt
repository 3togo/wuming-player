package com.wuming.tv.core.runtime.guardspider

import com.google.gson.Gson
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

data class GuardedSpiderProbeResult(
    val abi: GuardedSpiderAbi,
    val module: String,
    val dexProduced: Boolean,
    val loaderType: String?,
    val outputFiles: List<String>,
    val outputBytes: Long,
    val outputHeader: String?,
)

class GuardedSpiderRuntime(
    private val packagePath: Path,
    expectedSha256: String,
) : GuardedPayloadProducer {
    val packageReport: GuardedSpiderPackageReport =
        GuardedSpiderPackageInspector.inspect(packagePath, expectedSha256)

    fun probe(abi: GuardedSpiderAbi): GuardedSpiderProbeResult = execute(abi, "probe")

    fun decrypt(abi: GuardedSpiderAbi): GuardedSpiderProbeResult = execute(abi, "decrypt")

    fun decryptTo(abi: GuardedSpiderAbi, outputPayload: Path): GuardedSpiderProbeResult =
        execute(abi, "decrypt", outputPayload)

    override fun produce(abi: GuardedSpiderAbi, outputPayload: Path) {
        decryptTo(abi, outputPayload)
    }

    fun produceRuntimeAssets(abi: GuardedSpiderAbi, outputLibrary: Path, outputGuard: Path) {
        GuardedSpiderPackageInspector.extract(packagePath, packageReport, abi).use { extraction ->
            copyAtomically(extraction.library, outputLibrary)
            copyAtomically(extraction.guard, outputGuard)
        }
    }

    private fun copyAtomically(source: Path, target: Path) {
        target.parent?.let(Files::createDirectories)
        val temporary = target.resolveSibling("${target.fileName}.tmp")
        Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun execute(
        abi: GuardedSpiderAbi,
        operation: String,
        outputPayload: Path? = null,
    ): GuardedSpiderProbeResult {
        GuardedSpiderPackageInspector.extract(packagePath, packageReport, abi).use { extraction ->
            val java = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java")
            val errorLog = extraction.root.resolve("host-stderr.log")
            val process = ProcessBuilder(
                java.toString(),
                "-Xms32m",
                "-Xmx512m",
                "-Dfile.encoding=UTF-8",
                "-cp",
                System.getProperty("java.class.path"),
                GuardedSpiderNativeHostMain::class.java.name,
                abi.configName,
                extraction.library.toString(),
                extraction.guard.toString(),
                extraction.dataDirectory.toString(),
                operation,
            ).redirectError(errorLog.toFile()).start()
            process.outputStream.close()
            if (!process.waitFor(HOST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalArgumentException("Guarded spider ARM host timed out")
            }
            val stdout = process.inputStream.use { it.readAllBytes() }.toString(StandardCharsets.UTF_8).trim()
            val stderr = runCatching { Files.readString(errorLog) }.getOrDefault("")
            val responseLine = stdout.lineSequence().lastOrNull { it.trimStart().startsWith('{') }
                ?: throw IllegalArgumentException("Guarded spider ARM host returned no response: ${lastDiagnostic(stderr)}")
            val response = runCatching { Gson().fromJson(responseLine, HostResponse::class.java) }
                .getOrElse { throw IllegalArgumentException("Guarded spider ARM host returned invalid JSON") }
            if (!response.ok) {
                val reason = response.error?.detail?.lineSequence()
                    ?.filter { it.isNotBlank() }
                    ?.toList()
                    ?.takeLast(8)
                    ?.joinToString(" | ")
                    ?: response.error?.message
                    ?: lastDiagnostic(stderr).ifEmpty { "ARM emulation failed" }
                throw IllegalArgumentException("Guarded spider ARM host failed: $reason")
            }
            if (outputPayload != null) {
                check(response.dexProduced) { "Guarded spider did not produce a DEX payload" }
                require(Files.isRegularFile(extraction.decryptedDex)) { "Guarded spider DEX payload is missing" }
                outputPayload.parent?.let(Files::createDirectories)
                val temporary = outputPayload.resolveSibling("${outputPayload.fileName}.tmp")
                Files.copy(extraction.decryptedDex, temporary, StandardCopyOption.REPLACE_EXISTING)
                try {
                    Files.move(
                        temporary,
                        outputPayload,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary, outputPayload, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return GuardedSpiderProbeResult(
                abi,
                response.module.orEmpty(),
                response.dexProduced,
                response.loaderType,
                response.outputFiles,
                response.outputBytes,
                response.outputHeader,
            )
        }
    }

    private fun lastDiagnostic(stderr: String): String =
        stderr.lineSequence().map(String::trim).lastOrNull(String::isNotEmpty).orEmpty().take(500)

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", true)

    private data class HostResponse(
        val ok: Boolean = false,
        val module: String? = null,
        val dexProduced: Boolean = false,
        val loaderType: String? = null,
        val outputFiles: List<String> = emptyList(),
        val outputBytes: Long = 0,
        val outputHeader: String? = null,
        val error: HostError? = null,
    )

    private data class HostError(
        val kind: String? = null,
        val message: String? = null,
        val detail: String? = null,
    )

    private companion object {
        const val HOST_TIMEOUT_SECONDS = 90L
    }
}
