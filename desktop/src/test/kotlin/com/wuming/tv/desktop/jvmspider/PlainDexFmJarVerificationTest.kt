package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.guardspider.PlainDexSpiderPreparer
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import com.wuming.tv.desktop.config.DesktopTvBoxConfigurationLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Real-world check for the desktop plain-DEX pipeline fix.
 *
 * `fmys.json` publishes `csp_*` providers whose spider is `./lib/fm.jar` — an **unguarded** DEX
 * package. Before the fix the desktop rejected it with "spider runtime required" because only the
 * guarded native path existed. This test prepares that exact jar through the new
 * `PlainDexSpiderPreparer` and confirms every provider class is present and instantiable on the
 * desktop JVM.
 *
 * Inputs are the real artifacts downloaded from fmys.top:
 *   /tmp/fmys.json  (configuration)
 *   /tmp/fm.jar     (unguarded spider package, sha256 856c1b7b...)
 *
 * They are developer-supplied fixtures, not build outputs, so the test is *skipped* (not failed)
 * when they are absent. Nothing is deleted on exit: the preparer writes into the app's plain-DEX
 * cache (`plain-dex-spiders`), which is intentionally reused across runs — see `prepared.reused`.
 */
class PlainDexFmJarVerificationTest {

    private val configPath = Path.of("/tmp/fmys.json")
    private val jarPath = Path.of("/tmp/fm.jar")
    private val trustedSha256 = "856c1b7be73d57866accb18705d123ee0731c0f4b3fb10a114c163dad352764c"

    @Test
    fun loadsEveryCspProviderThroughPlainDexPipeline() {
        assumeTrue(
            "missing /tmp/fmys.json (download from http://fmys.top/fmys.json)",
            Files.isRegularFile(configPath),
        )
        assumeTrue(
            "missing /tmp/fm.jar (download from http://fmys.top/lib/fm.jar)",
            Files.isRegularFile(jarPath),
        )

        val config = DesktopTvBoxConfigurationLoader(
            cache = null,
            loadResource = { Files.readAllBytes(configPath) },
        ).load("/tmp/fmys.json")

        val spiderSites = config.jvmSpiderSites.filter { it.jvmExecution?.api?.startsWith("csp_") == true }
        println("fmys.json csp_* spider sites resolved: ${spiderSites.size}")

        val specs = spiderSites.map { site ->
            val exec = site.jvmExecution!!
            JvmSpiderSiteSpec(
                key = exec.key,
                name = exec.name,
                api = exec.api,
                extension = exec.extension,
                artifactLocation = "file://" + jarPath.toAbsolutePath().toString(),
                categories = exec.categories,
            )
        }
        val distinctApis = specs.map { it.api }.distinct()
        println("distinct csp_* provider classes: ${distinctApis.size}")

        // 1) The package must convert through dex2jar (the actual fix).
        val prepared = PlainDexSpiderPreparer.prepare(jarPath, trustedSha256)
        println("plain DEX prepare ok: classCount=${prepared.classCount} reused=${prepared.reused}")

        // 2) Every provider class must exist in the converted jar.
        val presentClasses = JarFile(prepared.path.toFile()).use { jar ->
            val names = jar.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name }
                .toSet()
            specs.associate { it.api to names.contains("${it.className.replace('.', '/')}.class") }
        }
        val missing = presentClasses.filterNot { it.value }.keys
        println("provider classes present: ${presentClasses.count { it.value }}/${presentClasses.size}")
        if (missing.isNotEmpty()) println("MISSING CLASSES: ${missing.sorted()}")

        // 3) Each present provider must instantiate on the desktop JVM (no native crypto needed).
        val loader = URLClassLoader(arrayOf(prepared.path.toUri().toURL()), this.javaClass.classLoader)
        val notInstantiable = presentClasses.filter { it.value }.mapNotNull { (api, _) ->
            val spec = specs.first { it.api == api }
            runCatching {
                val obj = loader.loadClass(spec.className).getDeclaredConstructor().newInstance()
                var parent = obj.javaClass
                var isSpider = false
                while (parent != null) {
                    if (parent.name == "com.github.catvod.crawler.Spider") { isSpider = true; break }
                    parent = parent.superclass
                }
                check(isSpider) { "${spec.className} is not a CatVod Spider" }
            }.exceptionOrNull()?.let { api to it.message }
        }
        if (notInstantiable.isNotEmpty()) {
            notInstantiable.sortedBy { it.first }.forEach { (api, msg) -> println("NOT INSTANTIABLE: $api -> $msg") }
        }

        // The fix: the unguarded package converts and every class it actually contains instantiates.
        assertTrue("plain DEX prepare must convert fm.jar", prepared.classCount > 0)
        // Two providers are declared in fmys.json but their classes are absent from fm.jar entirely
        // (config/jar drift in the upstream bundle). That gap is platform-independent: Android's
        // DexClassLoader would return SpiderNull for them too. Treat only those as acceptable.
        assertEquals(
            "only the upstream-missing classes may be absent (fix must not drop any real provider)",
            setOf("csp_XBPQ", "csp_Ypba"),
            missing,
        )
        assertTrue("every present provider must instantiate on the desktop JVM", notInstantiable.isEmpty())
    }
}
