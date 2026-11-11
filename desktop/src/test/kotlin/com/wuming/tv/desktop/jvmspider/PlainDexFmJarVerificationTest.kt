package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.guardspider.PlainDexSpiderPreparer
import com.wuming.tv.core.runtime.guardspider.GuardedProviderClassLoader
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import com.wuming.tv.core.source.config.TvBoxConfigDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.google.gson.JsonParser
import java.security.MessageDigest
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
 * The reviewed 2026-11-08 manifest pins the current JAR, a sanitized configuration snapshot and an
 * independent inventory of all 1,095 raw DEX classes. Run scripts/desktop/prepare-fm-fixture.py
 * to fetch the pinned binary into ignored artifacts, or supply -Dtvbox.fm.jar. Only absence skips;
 * mismatched bytes, dropped classes or constructor failures fail. Approved bytes remain unchanged.
 */
class PlainDexFmJarVerificationTest {

    private val jarPath = Path.of(System.getProperty("tvbox.fm.jar") ?: "../artifacts/desktop-provider-fixtures/fm.jar")
    private val manifest = JsonParser.parseString(resource("manifest.json")).asJsonObject
    private val trustedSha256 = manifest.get("jarSha256").asString

    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/guarded/fm-current/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test
    fun loadsEveryCspProviderThroughPlainDexPipeline() {
        assumeTrue(
            "Run scripts/desktop/prepare-fm-fixture.py or set -Dtvbox.fm.jar to the pinned JAR",
            Files.isRegularFile(jarPath),
        )

        val configText = resource("sites.json")
        assertEquals(manifest.get("configSha256").asString, sha256(configText.toByteArray(Charsets.UTF_8)))
        JarFile(jarPath.toFile()).use { jar ->
            val dex = jar.getInputStream(jar.getJarEntry("classes.dex")).use { it.readBytes() }
            assertEquals(manifest.get("dexSha256").asString, sha256(dex))
        }

        // Verify all declared provider APIs, including ones the UI now routes to built-in adapters.
        val spiderSites = TvBoxConfigDecoder.decode(configText).sites.filter { it.api.startsWith("csp_") }
        println("fmys.json csp_* spider sites resolved: ${spiderSites.size}")

        val specs = spiderSites.map { site ->
            JvmSpiderSiteSpec(
                key = site.key,
                name = site.name,
                api = site.api,
                extension = site.ext,
                artifactLocation = "file://" + jarPath.toAbsolutePath().toString(),
                categories = site.categories,
            )
        }
        val distinctApis = specs.map { it.api }.distinct()
        assertEquals(manifest.get("configuredSites").asInt, specs.size)
        assertEquals(manifest.get("configuredApis").asInt, distinctApis.size)
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
        val convertedClasses = JarFile(prepared.path.toFile()).use { jar ->
            jar.entries().asSequence().filter { it.name.endsWith(".class") }
                .map { it.name.removeSuffix(".class").replace('/', '.') }.toSet()
        }
        val rawDexClasses = manifest.getAsJsonArray("rawDexClassNames").map { it.asString }.toSet()
        assertEquals("Conversion must retain every independently inventoried DEX class", emptySet<String>(), rawDexClasses - convertedClasses)
        println("provider classes present: ${presentClasses.count { it.value }}/${presentClasses.size}")
        if (missing.isNotEmpty()) println("MISSING CLASSES: ${missing.sorted()}")

        // 3) Each present provider must instantiate on the desktop JVM (no native crypto needed).
        val notInstantiable = GuardedProviderClassLoader(arrayOf(prepared.path.toUri().toURL()), this.javaClass.classLoader).use { loader ->
          presentClasses.filter { it.value }.mapNotNull { (api, _) ->
            val spec = specs.first { it.api == api }
            runCatching {
                val obj = loader.loadClass(spec.className).getDeclaredConstructor().newInstance()
                var parent: Class<*>? = obj.javaClass
                var isSpider = false
                while (parent != null) {
                    if (parent.name == "com.github.catvod.crawler.Spider") { isSpider = true; break }
                    parent = parent.superclass
                }
                check(isSpider) { "${spec.className} is not a CatVod Spider" }
            }.exceptionOrNull()?.let { api to it.message }
          }
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
