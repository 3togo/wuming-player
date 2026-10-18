package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.catalog.CatalogVerificationProbe
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import org.junit.Assert.*
import org.junit.Test

class DesktopSpiderRuntimeTest {
    @Test fun portableUnknownSpiderRunsAndEarnsEvidenceThroughTheCommonAdapter() {
        val directory = Files.createTempDirectory("desktop-spider-adapter-")
        val prefs = Preferences.userRoot().node("desktop-spider-test/${UUID.randomUUID()}")
        try {
            val jar = compileSpider(directory)
            val store = DesktopSpiderVerificationStore(prefs)
            val runtime = DesktopSpiderRuntime(store)
            val site = JvmSpiderSiteSpec("demo", "Demo", "csp_Demo", "token", jar.toString())
            val fingerprint = runtime.inspect(site)
            assertEquals(SpiderArtifactKind.PORTABLE_CLASS_JAR, fingerprint.kind)
            val trust = GuardedSourceTrust(fingerprint.sha256, GuardedSpiderAbi.ARMEABI_V7A)
            val stale = trust.copy(sha256 = "0".repeat(64))
            for (failure in listOf(
                runCatching { runtime.activate(site, stale) }.exceptionOrNull(),
                runCatching { runtime.activateAll(listOf(site), stale) }.exceptionOrNull(),
            )) {
                assertTrue(failure is SpiderArtifactChangedException)
                assertEquals(stale.sha256, (failure as SpiderArtifactChangedException).approvedSha256)
                assertEquals(fingerprint.sha256, failure.currentSha256)
            }
            assertEquals(GuardedDesktopSupport.UNVERIFIED, GuardedDesktopSupportPolicy.classify(site))
            val executor = runtime.activate(site, trust)
            try {
                val checked = CatalogVerificationProbe.check(executor, true, false, "query")
                assertTrue(checked.catalogVerified(System.currentTimeMillis()))
                val identity = DesktopSpiderVerificationStore.identity(site, trust)
                assertEquals(GuardedDesktopSupport.VERIFIED_CATALOG, store.support(identity, false))
                assertTrue(executor.play("direct", "episode-id").supported)
                assertEquals(GuardedDesktopSupport.VERIFIED_PLAYBACK_URL, store.support(identity, false))
            } finally { (executor as AutoCloseable).close() }
        } finally {
            directory.toFile().deleteRecursively()
            prefs.removeNode()
            prefs.flush()
        }
    }

    private fun compileSpider(directory: Path): Path {
        val source = directory.resolve("Demo.java")
        Files.writeString(source, """
            package com.github.catvod.spider;
            import java.util.List;
            public class Demo {
                public void init(String extension) {
                    if (!"token".equals(extension)) throw new IllegalArgumentException("bad extension");
                }
                public String homeContent(boolean filter) {
                    if (!filter) throw new IllegalArgumentException("desktop must request types and filters");
                    return "{\"class\":[{\"type_id\":\"movie\",\"type_name\":\"Movies\"},{\"type_id\":\"series\",\"type_name\":\"Series\"}],\"list\":[{\"vod_id\":\"home-id\",\"vod_name\":\"Featured\"}]}";
                }
                public String categoryContent(String type, String page, boolean filter, java.util.HashMap<String, String> extend) {
                    return "{\"page\":" + page + ",\"pagecount\":3,\"list\":[{\"vod_id\":\"" + type + "-" + extend.get("year") + "\",\"vod_name\":\"Filtered\"}]}";
                }
                public String searchContent(String query, boolean quick) {
                    return "{\"list\":[{\"vod_id\":\"demo-id\",\"vod_name\":\"Demo result\",\"vod_remarks\":\"portable\"}]}";
                }
                public String detailContent(List<String> ids) {
                    return "{\"list\":[{\"vod_id\":\"demo-id\",\"vod_name\":\"Demo result\",\"vod_play_from\":\"direct\",\"vod_play_url\":\"Episode 1${'$'}episode-id\"}]}";
                }
                public String playerContent(String flag, String id, List<String> flags) {
                    return "{\"parse\":0,\"url\":\"https://media.example/video.m3u8\",\"header\":{\"User-Agent\":\"DesktopTest\"}}";
                }
            }
        """.trimIndent())
        val output = directory.resolve("classes")
        Files.createDirectories(output)
        val compiler = ToolProvider.getSystemJavaCompiler()
        check(compiler.run(null, null, null, "-d", output.toString(), source.toString()) == 0)
        val jar = directory.resolve("spider.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { archive ->
            Files.walk(output).use { paths ->
                paths.filter(Files::isRegularFile).forEach { file ->
                    archive.putNextEntry(JarEntry(output.relativize(file).toString().replace('\\', '/')))
                    Files.copy(file, archive)
                    archive.closeEntry()
                }
            }
        }
        return jar
    }
}
