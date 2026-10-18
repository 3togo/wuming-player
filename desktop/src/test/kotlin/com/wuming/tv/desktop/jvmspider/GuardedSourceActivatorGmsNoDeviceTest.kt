package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * No-device coverage for the GMS-relay vertical. When no `tvbox.guarded.gms.serial` is configured the
 * desktop keeps [com.wuming.tv.core.runtime.guardspider.DisabledAndroidRuntimeProviderBridge], so a
 * GMS-dependent provider the JVM cannot satisfy must surface the typed "needs Google Play Services"
 * [com.wuming.tv.core.catalog.CatalogPlayback] instead of routing to a device. This proves the
 * desktop degrades gracefully; the separately distributed provider may supply its own dependencies for the
 * relay when a device is wired.
 *
 * The synthetic `GmsProbe` fixture references `com.google.android.gms.common.api.Status`, which the
 * desktop JVM lacks, so its `play()` throws a GMS linkage error and the executor falls back to the
 * typed message (no adb / device needed).
 */
class GuardedSourceActivatorGmsNoDeviceTest {

    @Test
    fun playSurfacesTypedGmsMessageWhenNoDeviceWired() {
        System.clearProperty("tvbox.guarded.gms.serial")
        System.clearProperty("TVBOX_GUARDED_GMS_SERIAL")
        System.clearProperty("tvbox.guarded.emulator.serial")
        System.clearProperty("ANDROID_SERIAL")

        val resource = checkNotNull(javaClass.getResource("/guarded/GmsProbe-dexed.jar")) {
            "GmsProbe fixture jar missing from test resources"
        }
        val jarPath = Path.of(URI(resource.toString()))
        val sha256 = sha256(jarPath)

        val activator = GuardedSourceActivator()
        val site = JvmSpiderSiteSpec(
            key = "csp_GmsProbe",
            name = "GmsProbe",
            api = "csp_GmsProbe",
            artifactLocation = jarPath.toAbsolutePath().toString(),
        )
        val executor = activator.activate(site, sha256, GuardedSpiderAbi.ARMEABI_V7A)
        try {
            val playback = executor.play("", "probe-id")
            assertFalse(
                "Without a device, GMS-dependent play() must not report a supported playback; it must " +
                    "surface the typed Google Play Services message. Got: supported=${playback.supported} " +
                    "url=${playback.url} message=${playback.message}",
                playback.supported,
            )
            assertTrue(
                "GMS-dependent play() without a device must explain it needs Google Play Services. " +
                    "Got: ${playback.message}",
                playback.message.contains("Google Play Services", ignoreCase = true),
            )
        } finally {
            executor.close()
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { digest.update(it.readAllBytes()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
