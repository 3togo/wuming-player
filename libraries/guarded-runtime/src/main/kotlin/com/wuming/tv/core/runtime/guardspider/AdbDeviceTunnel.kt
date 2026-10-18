package com.wuming.tv.core.runtime.guardspider

import java.io.File
import java.net.Socket
import java.util.logging.Logger

/**
 * Shared adb / device / socket plumbing for the desktop -> device relay bridges.
 *
 * Both [AdbAndroidGmsProviderBridge] (GMS-dependent provider ops) and
 * [AdbAndroidEmulatorWebViewBridge] (WebView rendering) drive a connected Android device the same way:
 * `adb devices` liveness, `adb forward` tunnel, `am force-stop` + `am start` of the helper app, and a
 * socket poll until the helper listens. Centralising it keeps the two bridges behaviour-identical and
 * removes copy-paste drift; each bridge still owns its port, helper component, and op-specific
 * push/serialisation.
 */
internal object AdbDeviceTunnel {
    private val logger = Logger.getLogger("tvbox.guarded.adb")

    fun resolveAdb(): String {
        val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (sdk != null) {
            val candidate = java.io.File(sdk, "platform-tools/adb")
            if (candidate.canExecute()) return candidate.absolutePath
        }
        return "adb"
    }

    fun runAdb(adbExecutable: String, vararg args: String): String? = runCatching {
        val command = buildList {
            add(adbExecutable)
            args.forEach { add(it) }
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    }.getOrNull()

    fun deviceOnline(adbExecutable: String, serial: String): Boolean {
        val output = runAdb(adbExecutable, "devices") ?: return false
        return output.lineSequence().map { it.trim() }
            .any { it.startsWith("$serial\tdevice") || it.startsWith("$serial device") }
    }

    fun setupForward(adbExecutable: String, serial: String, port: Int) {
        // Idempotent; an already-forwarded port returns non-zero, which we ignore.
        runAdb(adbExecutable, "-s", serial, "forward", "tcp:$port", "tcp:$port")
    }

    fun launchHelper(adbExecutable: String, serial: String, component: String, forceStop: Boolean) {
        if (forceStop) {
            // Force-stop first so a previously running helper (still holding an older pushed artifact
            // in its DexClassLoader) reloads the just-pushed jar instead of serving a stale provider.
            runAdb(adbExecutable, "-s", serial, "shell", "am", "force-stop", component.substringBefore("/"))
        }
        runAdb(adbExecutable, "-s", serial, "shell", "am", "start", "-n", component)
    }

    fun waitForSocket(port: Int, attempts: Int, intervalMs: Long): Boolean {
        repeat(attempts) {
            if (canConnect(port)) return true
            Thread.sleep(intervalMs)
        }
        return canConnect(port)
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        Socket("127.0.0.1", port).use { true }
    }.getOrDefault(false)
}
