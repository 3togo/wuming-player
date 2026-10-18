package com.wuming.tv.desktop

import com.wuming.tv.core.runtime.guardspider.GuardedCatVodCatalogSourceExecutor
import com.wuming.tv.core.runtime.guardspider.GuardedCatVodSiteSpec
import com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi
import com.wuming.tv.core.runtime.guardspider.PreparedGuardedSpiderArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class GuardedCatVodIntegrationTest {

    @Test
    fun executesSearchDetailAndPlaybackInCompatibilityProcess() {
        val root = Files.createTempDirectory("guarded-catvod-integration-")
        try {
            val jar = compileFixture(root)
            val artifact = PreparedGuardedSpiderArtifact(
                path = jar,
                abi = GuardedSpiderAbi.ARMEABI_V7A,
                packageSha256 = "f".repeat(64),
                artifactSha256 = "e".repeat(64),
                classCount = 1,
                reused = true,
            )
            val spec = GuardedCatVodSiteSpec("fixture", "Fixture", "csp_FixtureGuard", "desktop")
            GuardedCatVodCatalogSourceExecutor(spec, artifact).use { executor ->
                val item = executor.search("demo").single()
                assertEquals("fixture", item.sourceKey)
                assertEquals("demo-id", item.id)
                val detail = executor.detail(item.id)
                assertEquals(1, detail.lines.size)
                assertEquals("episode-id", detail.lines.single().episodes.single().id)
                val playback = executor.play("direct", "episode-id")
                assertTrue(playback.supported)
                assertEquals("https://media.example/video.m3u8", playback.url)
                assertEquals("Fixture", playback.headers["User-Agent"])
            }
        } finally {
            Files.walk(root).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun compileFixture(root: Path): Path {
        val source = root.resolve("src/com/github/catvod/spider/Fixture.java")
        val classes = root.resolve("classes")
        Files.createDirectories(source.parent)
        Files.createDirectories(classes)
        Files.writeString(source, FIXTURE_SOURCE)
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("JDK compiler is unavailable")
        val result = compiler.run(
            null,
            null,
            null,
            "-classpath",
            System.getProperty("java.class.path"),
            "-d",
            classes.toString(),
            source.toString(),
        )
        check(result == 0) { "Fixture provider compilation failed" }
        val jar = root.resolve("fixture.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            Files.walk(classes).filter(Files::isRegularFile).forEach { file ->
                output.putNextEntry(JarEntry(classes.relativize(file).toString().replace('\\', '/')))
                Files.copy(file, output)
                output.closeEntry()
            }
        }
        return jar
    }

    private companion object {
        val FIXTURE_SOURCE = """
            package com.github.catvod.spider;
            import java.util.List;
            public final class Fixture extends com.github.catvod.crawler.Spider {
                private String extension;
                public void init(android.content.Context context, String extension) {
                    this.extension = extension;
                    if (!context.getFilesDir().isDirectory()) throw new IllegalStateException("missing files dir");
                }
                public String searchContent(String query, boolean quick) {
                    return "{\"list\":[{\"vod_id\":\"demo-id\",\"vod_name\":\"" + query + " " + extension + "\"}]}";
                }
                public String detailContent(List<String> ids) {
                    return "{\"list\":[{\"vod_id\":\"" + ids.get(0) + "\",\"vod_name\":\"Demo\",\"vod_play_from\":\"direct\",\"vod_play_url\":\"Episode\u0024episode-id\"}]}";
                }
                public String playerContent(String flag, String id, List<String> vipFlags) {
                    return "{\"parse\":0,\"url\":\"https://media.example/video.m3u8\",\"header\":{\"User-Agent\":\"Fixture\"}}";
                }
            }
        """.trimIndent()
    }
}
