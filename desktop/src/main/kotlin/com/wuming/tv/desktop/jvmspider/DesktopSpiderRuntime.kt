package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.catalog.CatalogSourceExecutor
import com.wuming.tv.core.catalog.VerifyingCatalogSourceExecutor
import com.wuming.tv.core.runtime.jvmspider.JvmCatVodCatalogSourceExecutor
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec

/** Desktop port selecting an existing isolated host, with the same catalogue contract for each. */
class DesktopSpiderRuntime(
    private val evidence: DesktopSpiderVerificationStore,
    private val guarded: GuardedSourceActivator = GuardedSourceActivator(),
    private val onEvidenceChanged: () -> Unit = {},
) {
    fun inspect(site: JvmSpiderSiteSpec): SpiderArtifactFingerprint =
        TrustedJvmSpiderArtifact.resolve(site.artifactLocation).use { SpiderArtifactFingerprinter.inspect(it.path) }

    fun activate(site: JvmSpiderSiteSpec, trust: GuardedSourceTrust): CatalogSourceExecutor {
        val artifact = TrustedJvmSpiderArtifact.resolve(site.artifactLocation)
        try {
            val fingerprint = SpiderArtifactFingerprinter.inspect(artifact.path)
            if (fingerprint.sha256 != trust.sha256) throw SpiderArtifactChangedException(trust.sha256, fingerprint.sha256)
            val executor = when (fingerprint.kind) {
                SpiderArtifactKind.PORTABLE_CLASS_JAR -> JvmCatVodCatalogSourceExecutor(site, artifact.path, trust.sha256)
                SpiderArtifactKind.PLAIN_DEX_PACKAGE, SpiderArtifactKind.GUARDED_DEX_PACKAGE ->
                    guarded.activate(site.copy(artifactLocation = artifact.path.toString()), trust.sha256, trust.abi)
                SpiderArtifactKind.UNKNOWN -> error("Unsupported spider package: expected a portable JVM JAR or supported Android DEX bundle.")
            }
            return observe(executor, site, trust, artifact::close)
        } catch (failure: Throwable) {
            artifact.close()
            throw failure
        }
    }

    fun activateAll(sites: List<JvmSpiderSiteSpec>, trust: GuardedSourceTrust): List<CatalogSourceExecutor> {
        require(sites.isNotEmpty() && sites.all { it.artifactLocation == sites.first().artifactLocation })
        val fingerprint = inspect(sites.first())
        if (fingerprint.sha256 != trust.sha256) throw SpiderArtifactChangedException(trust.sha256, fingerprint.sha256)
        if (fingerprint.kind == SpiderArtifactKind.GUARDED_DEX_PACKAGE || fingerprint.kind == SpiderArtifactKind.PLAIN_DEX_PACKAGE) {
            val byKey = sites.associateBy { it.key }
            return guarded.activateAll(sites, trust.sha256, trust.abi).map {
                observe(it, byKey.getValue(it.source.key), trust)
            }
        }
        val activated = mutableListOf<CatalogSourceExecutor>()
        try {
            sites.forEach { activated += activate(it, trust) }
            return activated
        } catch (failure: Throwable) {
            activated.forEach { runCatching { (it as? AutoCloseable)?.close() } }
            throw failure
        }
    }

    fun observe(
        executor: CatalogSourceExecutor,
        site: JvmSpiderSiteSpec,
        trust: GuardedSourceTrust,
        closeResource: () -> Unit = {},
    ): CatalogSourceExecutor {
        val identity = DesktopSpiderVerificationStore.identity(site, trust)
        return VerifyingCatalogSourceExecutor(executor, {
            evidence.record(identity, it)
            onEvidenceChanged()
        }, closeResource)
    }
}

/** Expected approval state, distinct from provider crashes or download failures. */
class SpiderArtifactChangedException(val approvedSha256: String, val currentSha256: String) :
    IllegalArgumentException("Provider bundle updated; review and approve its new SHA-256 in Settings.")
