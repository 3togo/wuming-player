package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.guardspider.AdbAndroidEmulatorWebViewBridge
import com.wuming.tv.core.runtime.guardspider.AdbAndroidGmsProviderBridge
import com.wuming.tv.core.runtime.guardspider.AndroidEmulatorWebViewBridge
import com.wuming.tv.core.runtime.guardspider.AndroidRuntimeProviderBridge
import com.wuming.tv.core.runtime.guardspider.DisabledAndroidRuntimeProviderBridge
import com.wuming.tv.core.runtime.guardspider.DisabledEmulatorWebViewBridge
import com.wuming.tv.core.runtime.guardspider.GuardedCatVodCatalogSourceExecutor
import com.wuming.tv.core.runtime.guardspider.GuardedCatVodSiteSpec
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.guardspider.UnsupportedGuardedSpiderPackage
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderPreparer
import com.wuming.tv.core.runtime.guardspider.PlainDexSpiderPreparer
import com.wuming.tv.core.runtime.guardspider.PreparedGuardedSpiderArtifact
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderRuntime
import com.wuming.tv.core.runtime.guardspider.GuardedRuntimeAssetsProducer
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import java.util.logging.Logger
import java.util.jar.JarFile
import java.util.concurrent.Executors

/** Explicit-trust bridge from an inspected remote bundle to one isolated session executor. */
class GuardedSourceActivator {
    private val logger = Logger.getLogger("tvbox.guarded.activation")

    /**
     * Android resolves every `csp_*` api against the configured spider jar, so the `Guard`
     * suffix some configurations use is a naming convention rather than a capability. The real
     * gates stay unchanged: the bundle must inspect as a guarded package and the provider must
     * be listed in [GuardedDesktopSupportPolicy] before activation is offered.
     */
    fun isCandidate(site: JvmSpiderSiteSpec): Boolean = site.api.startsWith("csp_")

    fun inspect(site: JvmSpiderSiteSpec): SpiderArtifactFingerprint {
        logger.info("inspect start key=${site.key} class=${site.className}")
        require(isCandidate(site)) {
            "${site.className} is not a guarded desktop candidate"
        }
        return TrustedJvmSpiderArtifact.resolve(site.artifactLocation).use { artifact ->
            SpiderArtifactFingerprinter.inspect(artifact.path).also {
                require(it.kind == SpiderArtifactKind.GUARDED_DEX_PACKAGE ||
                    it.kind == SpiderArtifactKind.PLAIN_DEX_PACKAGE) {
                    "Selected source is not an Android DEX spider package"
                }
                logger.info("inspect success key=${site.key} sha256=${it.sha256} bytes=${it.bytes} abis=${it.supportedAbis}")
            }
        }
    }

    fun activate(
        site: JvmSpiderSiteSpec,
        trustedSha256: String,
        abi: GuardedSpiderAbi,
    ): GuardedCatVodCatalogSourceExecutor = activateAll(listOf(site), trustedSha256, abi).single()

    /** Resolves and prepares one exact bundle once, then starts one isolated host per provider. */
    fun activateAll(
        sites: List<JvmSpiderSiteSpec>,
        trustedSha256: String,
        abi: GuardedSpiderAbi,
    ): List<GuardedCatVodCatalogSourceExecutor> {
        require(sites.isNotEmpty()) { "At least one guarded desktop source is required" }
        sites.forEach { site ->
            require(isCandidate(site)) { "${site.className} is not a guarded desktop candidate" }
        }
        val artifactLocation = sites.first().artifactLocation
        require(sites.all { it.artifactLocation == artifactLocation }) {
            "A guarded activation batch must use one exact artifact"
        }
        logger.info(
            "activate batch start sources=${sites.size} abi=${abi.configName} trustedSha256=$trustedSha256",
        )
        return TrustedJvmSpiderArtifact.resolve(artifactLocation).use { artifact ->
            val prepared = prepare(artifact.path, trustedSha256, abi)
            val workerCount = minOf(MAX_PARALLEL_PROVIDER_STARTS, sites.size)
            val workers = Executors.newFixedThreadPool(workerCount) { task ->
                Thread(task, "tvbox-guarded-activation").apply { isDaemon = true }
            }
            val emulatorBridge = resolveEmulatorBridge()
            if (emulatorBridge is AdbAndroidEmulatorWebViewBridge) {
                val wiredSerial = System.getProperty("tvbox.guarded.emulator.serial") ?: System.getenv("ANDROID_SERIAL")
                val ready = emulatorBridge.prepare()
                logger.info("emulator tunnel prepare serial=$wiredSerial ready=$ready")
            }
            val gmsBridge = resolveGmsBridge(prepared)
            if (gmsBridge is AdbAndroidGmsProviderBridge) {
                val wiredSerial = System.getProperty("tvbox.guarded.gms.serial") ?: System.getenv("TVBOX_GUARDED_GMS_SERIAL")
                val ready = gmsBridge.prepare()
                logger.info("gms tunnel prepare serial=$wiredSerial ready=$ready")
            }
            val futures = sites.map { site ->
                site to workers.submit<GuardedCatVodCatalogSourceExecutor> {
                    val guardedSite = GuardedCatVodSiteSpec(
                        site.key,
                        site.name,
                        site.api,
                        site.extension,
                        site.categories,
                        site.providerKey,
                    )
                    requireProviderClass(prepared.path, guardedSite.className)
                    GuardedCatVodCatalogSourceExecutor(guardedSite, prepared, emulatorBridge, gmsBridge).also {
                        logger.info("activate success key=${site.key} abi=${abi.configName} cacheReused=${prepared.reused} emulatorBridge=${emulatorBridge::class.simpleName}")
                    }
                }
            }
            workers.shutdown()
            val activated = mutableListOf<GuardedCatVodCatalogSourceExecutor>()
            val failed = mutableListOf<Pair<String, Throwable>>()
            futures.forEach { (site, future) ->
                runCatching { future.get() }
                    .onSuccess(activated::add)
                    .onFailure { failure ->
                        val cause = failure.cause ?: failure
                        failed += site.key to cause
                        logger.warning("activate failed key=${site.key} error=${cause.message}")
                    }
            }
            if (activated.isEmpty() && failed.isNotEmpty()) {
                throw failed.first().second
            }
            if (failed.isNotEmpty()) {
                logger.warning("activate partial success ok=${activated.size} failed=${failed.size}")
            }
            activated
        }
    }

    /**
     * Guarded packages need the native guard and an ARM library; a plain `classes.dex` package
     * needs nothing but a dex2jar pass. Both end in the same isolated host, so callers see one
     * activation path.
     */
    private fun prepare(
        artifact: java.nio.file.Path,
        trustedSha256: String,
        abi: GuardedSpiderAbi,
    ): PreparedGuardedSpiderArtifact {
        val guarded = runCatching {
            val runtime = GuardedSpiderRuntime(artifact, trustedSha256)
            require(abi in runtime.packageReport.supportedAbis) {
                "Guarded package does not contain ${abi.configName}"
            }
            GuardedSpiderPreparer(
                runtime.packageReport,
                runtime,
                GuardedRuntimeAssetsProducer(runtime::produceRuntimeAssets),
            ).prepare(abi)
        }
        return guarded.getOrElse { guardedFailure ->
            val plain = runCatching { PlainDexSpiderPreparer.prepare(artifact, trustedSha256) }
            plain.getOrElse { plainFailure ->
                throw if (guardedFailure is UnsupportedGuardedSpiderPackage) plainFailure else guardedFailure
            }
        }
    }

    /**
     * When a device serial is configured (`tvbox.guarded.emulator.serial` or `ANDROID_SERIAL`) the
     * desktop routes WebView-only providers to that device via [AdbAndroidEmulatorWebViewBridge];
     * otherwise the executor keeps the no-op [DisabledEmulatorWebViewBridge] and surfaces a typed
     * `requiresEmulator` signal.
     */
    private fun resolveEmulatorBridge(): AndroidEmulatorWebViewBridge {
        val serial = System.getProperty("tvbox.guarded.emulator.serial") ?: System.getenv("ANDROID_SERIAL")
        return if (!serial.isNullOrBlank()) AdbAndroidEmulatorWebViewBridge(serial) else DisabledEmulatorWebViewBridge
    }

    /**
     * When a device serial is configured for GMS (`tvbox.guarded.gms.serial` or
     * `TVBOX_GUARDED_GMS_SERIAL`) the desktop routes GMS-dependent providers (whose `detail`/`play`
     * link against Google Play Services the JVM lacks) to that device via [AdbAndroidGmsProviderBridge];
     * otherwise the executor keeps [DisabledAndroidRuntimeProviderBridge] and surfaces a typed
     * GMS-only signal.
     */
    private fun resolveGmsBridge(prepared: PreparedGuardedSpiderArtifact): AndroidRuntimeProviderBridge {
        val serial = System.getProperty("tvbox.guarded.gms.serial") ?: System.getenv("TVBOX_GUARDED_GMS_SERIAL")
        return if (!serial.isNullOrBlank()) AdbAndroidGmsProviderBridge(serial, prepared) else DisabledAndroidRuntimeProviderBridge
    }

    private fun requireProviderClass(artifact: java.nio.file.Path, className: String) {
        val entry = className.replace('.', '/') + ".class"
        JarFile(artifact.toFile(), true).use { jar ->
            require(jar.getJarEntry(entry) != null) {
                "Guarded package does not contain provider $className"
            }
        }
    }

    private companion object {
        const val MAX_PARALLEL_PROVIDER_STARTS = 8
    }
}
