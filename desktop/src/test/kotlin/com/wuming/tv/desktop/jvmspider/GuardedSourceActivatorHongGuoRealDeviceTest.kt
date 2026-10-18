package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Real-provider end-to-end validation of the desktop GMS-relay vertical: activate the actual 红果
 * plain-DEX provider (type 3, shares the config's `fm.jar`) through the real [GuardedSourceActivator]
 * and route its GMS-dependent `detail`/`play` to a wired device (`7e34269d`, which has Google Play
 * Services). This goes beyond the synthetic `GmsProbe` hardware test by exercising a production provider.
 *
 * The 红果 vod id is harvested automatically: first from the desktop `home()`, then — because 红果's
 * `home`/`category`/`search` return 0 on the desktop JVM — directly from the wired GMS device's
 * `home`/`category` (via [com.wuming.tv.core.runtime.guardspider.GuardedCatVodCatalogSourceExecutor.deviceHomeIds]).
 * A manual id may still be forced with `-Dtvbox.hongguo.vodId=<id>`. That value is tolerated in several
 * shapes: a bare vod id, a `detail` logcat line (`key=csp_HongGuo,id=<id>,...`), or a share URL (the id
 * is taken from a `?id=` query param or the last path segment, with a trailing `.html` stripped). So a
 * 红果 share link pasted verbatim also works.
 *
 * Runs only when `-Dtvbox.guarded.hardware.test.serial=<serial>` is supplied.
 */
class GuardedSourceActivatorHongGuoRealDeviceTest {

    @Test
    fun desktopRoutesRealHongGuoDetailAndPlayToDevice() {
        val serial = System.getProperty("tvbox.guarded.hardware.test.serial")
        Assume.assumeNotNull(
            "Set -Dtvbox.guarded.hardware.test.serial and install guarded-spider-helper to run this hardware test",
            serial,
        )
        System.setProperty("tvbox.guarded.gms.serial", serial!!)
        System.clearProperty("TVBOX_GUARDED_GMS_SERIAL")
        System.clearProperty("tvbox.guarded.emulator.serial")
        System.clearProperty("ANDROID_SERIAL")

        val jarPath = Path.of(
            System.getProperty("tvbox.hongguo.jar")
                ?: "/tmp/hongguo/fm.jar",
        )
        Assume.assumeTrue("红果 fm.jar not found at $jarPath (download it or set -Dtvbox.hongguo.jar)", Files.isRegularFile(jarPath))
        val sha256 = sha256(jarPath)

        val activator = GuardedSourceActivator()
        val site = JvmSpiderSiteSpec(
            key = "红果短剧",
            name = "🍎┃红果┃短剧",
            api = "csp_HongGuo",
            artifactLocation = jarPath.toAbsolutePath().toString(),
        )
        val executor = activator.activate(site, sha256, GuardedSpiderAbi.ARMEABI_V7A)
        try {
            val homeItems = executor.home()
            println("HONGGUO_HOME_ITEMS_START count=${homeItems.size}")
            homeItems.take(20).forEach { println("id=${it.id} name=${it.name}") }
            println("HONGGUO_HOME_ITEMS_END")

            val deviceIds = if (homeItems.isEmpty()) executor.deviceHomeIds() else emptyList()
            println("HONGGUO_DEVICE_IDS_START count=${deviceIds.size}")
            deviceIds.take(20).forEach { println("deviceId=$it") }
            println("HONGGUO_DEVICE_IDS_END")

            val vodId = homeItems.firstOrNull()?.id
                ?: deviceIds.firstOrNull()
                ?: resolveVodId(System.getProperty("tvbox.hongguo.vodId"))
            assertNotNull(
                "No 红果 vod id harvestable from desktop home() nor from the wired device's home()/category(), " +
                    "and none supplied via -Dtvbox.hongguo.vodId. Force one with -Dtvbox.hongguo.vodId=<id>.",
                vodId,
            )
            println("HONGGUO_USING_VOD_ID=$vodId")

            val detail = executor.detail(vodId!!)
            println("HONGGUO_DETAIL name=${detail.item.name} lines=${detail.lines.size}")
            detail.lines.forEach { line ->
                println("  line flag=${line.flag} name=${line.name} episodes=${line.episodes.size}")
            }
            assertFalse("Device-resolved 红果 detail must carry at least one play line", detail.lines.isEmpty())

            val line = detail.lines.first()
            val episode = line.episodes.first()
            val playback = executor.play(line.flag, episode.id)
            println("HONGGUO_PLAY supported=${playback.supported} url=${playback.url} format=${playback.format} message=${playback.message}")
            assertTrue(
                "红果 play() must be resolved on the device (GMS relay) and report a supported playback. " +
                    "Got: supported=${playback.supported} url=${playback.url} message=${playback.message}",
                playback.supported,
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

    /**
     * Tolerantly extract a 红果 vod id from whatever the caller pasted:
     *  - a bare id (returned as-is),
     *  - a `detail` logcat line such as `key=csp_HongGuo,id=<id>,...` → `<id>`,
     *  - a share URL → the `?id=` query param, else the last non-empty path segment with `.html` stripped.
     * Returns null when [raw] is blank.
     */
    private fun resolveVodId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim().removeSurrounding("\"").removeSurrounding("'")
        Regex("""[?&, ]id=([^,;\s]+)""").find(trimmed)?.groupValues?.get(1)?.let { return it }
        if (trimmed.contains("://") || trimmed.startsWith("http", ignoreCase = true)) {
            runCatching {
                val uri = URI(trimmed)
                uri.query?.let { q ->
                    Regex("""(^|&)id=([^&]+)""").find(q)?.groupValues?.get(2)?.let { return it }
                }
                uri.path?.split("/")?.filter { it.isNotBlank() }?.lastOrNull()
                    ?.substringBefore(".html")?.let { return it }
            }
        }
        return trimmed
    }
}
