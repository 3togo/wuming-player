package com.wuming.tv.core.runtime.guardspider

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Detects the GMS-missing signal emitted by the guarded host when a provider's `detail`/`play`
 * links against `com.google.android.gms.*` classes that are absent from the desktop JVM.
 *
 * Unlike the WebView case (where only rendering is offloaded), a GMS-dependent provider needs the
 * *whole* operation to run on a real Android runtime that has Google Play Services. There is no
 * functional open GMS implementation for a bare desktop JVM (microG is Android-only; any TVBox
 * fork gms stub is link-only). The only working route is to run the op on a connected Android
 * device/emulator and relay the JSON result back — mirroring [AdbAndroidEmulatorWebViewBridge].
 */
object GmsMarker {
    fun matches(message: String?): Boolean {
        if (message == null) return false
        val gms = "com.google.android.gms" in message || "com/google/android/gms" in message
        val linkage = "ClassNotFoundException" in message ||
            "NoClassDefFoundError" in message ||
            "Could not initialize class" in message
        return gms && linkage
    }
}

/**
 * Pluggable bridge used when a guarded spider's `detail`/`play` needs Google Play Services the
 * desktop host lacks. The default [DisabledAndroidRuntimeProviderBridge] is a no-op; a real
 * implementation ([AdbAndroidGmsProviderBridge]) drives a connected Android device/emulator and
 * returns the provider JSON result.
 */
interface AndroidRuntimeProviderBridge {
    val available: Boolean
    fun runOnDevice(spec: GuardedCatVodSiteSpec, request: GuardedCatVodHostRequest): String?
}

object DisabledAndroidRuntimeProviderBridge : AndroidRuntimeProviderBridge {
    override val available: Boolean get() = false
    override fun runOnDevice(spec: GuardedCatVodSiteSpec, request: GuardedCatVodHostRequest): String? = null
}

/**
 * Real [AndroidRuntimeProviderBridge] that runs a GMS-dependent provider operation on a connected
 * Android device/emulator (which has Google Play Services) and relays the JSON result back over an
 * `adb forward` socket. The device side is a new `guarded-spider-helper` app that loads the *same*
 * prepared DEX the desktop host would have used and runs the op with real GMS.
 *
 * Mirrors [AdbAndroidEmulatorWebViewBridge]: the bridge is constructed only when a serial is
 * configured via `tvbox.guarded.gms.serial` (or `TVBOX_GUARDED_GMS_SERIAL`); [prepare] pushes the
 * prepared artifact and brings up the helper tunnel before the first op.
 */
class AdbAndroidGmsProviderBridge(
    private val serial: String,
    private val artifact: PreparedGuardedSpiderArtifact,
    private val adbExecutable: String = AdbDeviceTunnel.resolveAdb(),
) : AndroidRuntimeProviderBridge {
    private val logger = Logger.getLogger("tvbox.guarded.gms")
    private val gson = Gson()
    private val ready = AtomicBoolean(false)

    override val available: Boolean
        get() = serial.isNotBlank() && AdbDeviceTunnel.deviceOnline(adbExecutable, serial)

    /** Proactively push the artifact and bring up the helper tunnel before the first op. */
    fun prepare(): Boolean = ensureReady()

    override fun runOnDevice(spec: GuardedCatVodSiteSpec, request: GuardedCatVodHostRequest): String? {
        if (!ensureReady()) return null
        return runCatching {
            Socket("127.0.0.1", GMS_BRIDGE_PORT).use { socket ->
                val out = socket.getOutputStream().writer(StandardCharsets.UTF_8)
                val inp = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                val message = gson.toJson(
                    mapOf(
                        "request" to request,
                        "artifact" to artifact.path.toAbsolutePath().toString(),
                        "className" to spec.className,
                        "key" to spec.key,
                        "extension" to spec.extension,
                        "abi" to artifact.abi.configName,
                        "nativeLibrary" to (artifact.nativeLibrary?.toAbsolutePath()?.toString().orEmpty()),
                        "guardData" to (artifact.guardData?.toAbsolutePath()?.toString().orEmpty()),
                    ),
                )
                out.write(message)
                out.write("\n")
                out.flush()
                val line = inp.readLine() ?: return null
                unwrap(line)
            }
        }.onFailure { logger.warning("gms runOnDevice failed key=${spec.key} error=${it.message}") }
            .getOrNull()
    }

    /**
     * The device helper answers with `{"ok":bool,"result":<raw spider json>,"error":<msg>}`; the
     * catalog codec expects the *raw* spider JSON (the inner `result`), so unwrap it. On a device
     * error we return null and let the executor degrade.
     */
    private fun unwrap(line: String): String? {
        val envelope = runCatching { gson.fromJson(line, JsonObject::class.java) }.getOrNull() ?: return null
        return if (envelope.get("ok")?.asBoolean == true) envelope.get("result")?.asString else null
    }

    private fun ensureReady(): Boolean {
        if (ready.get()) return true
        if (serial.isBlank() || !AdbDeviceTunnel.deviceOnline(adbExecutable, serial)) return false
        pushArtifact()
        AdbDeviceTunnel.setupForward(adbExecutable, serial, GMS_BRIDGE_PORT)
        AdbDeviceTunnel.launchHelper(adbExecutable, serial, HELPER_COMPONENT, forceStop = true)
        if (!AdbDeviceTunnel.waitForSocket(GMS_BRIDGE_PORT, SOCKET_POLL_ATTEMPTS, SOCKET_POLL_INTERVAL_MS)) return false
        ready.set(true)
        logger.info("gms tunnel ready device=$serial")
        return true
    }

    private fun pushArtifact() {
        val spider = dexArtifact() ?: artifact.path
        AdbDeviceTunnel.runAdb(adbExecutable, "-s", serial, "push", spider.toAbsolutePath().toString(), DEVICE_SPIDER_PATH)
        artifact.nativeLibrary?.let {
            AdbDeviceTunnel.runAdb(adbExecutable, "-s", serial, "push", it.toAbsolutePath().toString(), DEVICE_NATIVE_PATH)
        }
        artifact.guardData?.let {
            AdbDeviceTunnel.runAdb(adbExecutable, "-s", serial, "push", it.toAbsolutePath().toString(), DEVICE_GUARD_PATH)
        }
    }

    /**
     * The desktop consumes a dex2jar `.class` JAR; Android's [dalvik.system.DexClassLoader] can only
     * load a real `classes.dex`. Convert the prepared artifact to a DEX JAR with the Android SDK `d8`
     * tool (cached next to the artifact, **keyed by the content hash of the source** so a changed
     * provider can never silently reuse a stale DEX) so the device helper can load the provider.
     * Falls back to the `.class` JAR (which the device cannot load) only when `d8` is unavailable.
     */
    private fun dexArtifact(): Path? {
        val sourceHash = sha256OrNull(artifact.path) ?: run {
            logger.warning("unable to read source artifact for hashing; pushing class JAR (Android cannot load it)")
            return null
        }
        val cached = artifact.path.parent.resolve("spider-android-$sourceHash.dex.jar")
        if (Files.isRegularFile(cached)) {
            logger.info("d8 cache hit source=${artifact.path.fileName} hash=$sourceHash")
            return cached
        }
        val d8 = findD8() ?: run {
            logger.warning("d8 not found on ANDROID_HOME/build-tools; pushing class JAR (Android cannot load it)")
            return null
        }
        return runCatching {
            val command = listOf(
                d8, "--release",
                "--output", cached.toAbsolutePath().toString(),
                artifact.path.toAbsolutePath().toString(),
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            if (Files.isRegularFile(cached)) cached else null
        }.onFailure { logger.warning("d8 conversion failed: ${it.message}") }.getOrNull()
    }

    private fun findD8(): String? {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
        val buildTools = File(sdk, "build-tools")
        if (buildTools.isDirectory) {
            buildTools.listFiles()?.forEach { version ->
                val candidate = File(version, "d8")
                if (candidate.canExecute()) return candidate.absolutePath
            }
        }
        return runCatching {
            val process = ProcessBuilder("bash", "-lc", "command -v d8")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText().trim() }.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private companion object {
        const val GMS_BRIDGE_PORT = 8766
        val HELPER_COMPONENT = com.wuming.tv.core.base.EditionSettings.current.helperComponent("guardspider")
        val HELPER_PACKAGE = com.wuming.tv.core.base.EditionSettings.current.helperPackage("guardspider")
        const val DEVICE_SPIDER_PATH = "/data/local/tmp/tvbox-guarded/spider.jar"
        const val DEVICE_NATIVE_PATH = "/data/local/tmp/tvbox-guarded/guard.so"
        const val DEVICE_GUARD_PATH = "/data/local/tmp/tvbox-guarded/guard.bin"
        const val SOCKET_POLL_ATTEMPTS = 20
        const val SOCKET_POLL_INTERVAL_MS = 250L
    }
}
