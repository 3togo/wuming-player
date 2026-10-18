package com.wuming.tv.core.runtime.jvmspider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

class JvmCatVodCatalogSourceExecutorTest {

    @Test
    fun invokesPortableSpiderThroughReflection() {
        val directory = Files.createTempDirectory("jvm-spider-test-")
        try {
            val jar = compileSpider(directory)
            val sha256 = JvmSpiderArtifactInspector.inspect(jar).sha256
            val spec = JvmSpiderSiteSpec(
                "demo",
                "Demo",
                "csp_Demo",
                "token",
                jar.toString(),
                listOf("Series"),
                providerKey = "original-provider",
            )

            JvmCatVodCatalogSourceExecutor(spec, jar, sha256).use { executor ->
                assertEquals("Featured", executor.home().single().name)
                val home = executor.browseHome()
                assertEquals("Series", home.types.single().name)
                val category = executor.category("movie", 2, mapOf("year" to "2026"))
                assertEquals("movie-2026", category.items.single().id)
                assertEquals(2, category.page)
                val item = executor.search("query").single()
                assertEquals("Demo result", item.name)
                val detail = executor.detail(item.id)
                assertEquals(1, detail.lines.size)
                assertEquals("episode-id", detail.lines.single().episodes.single().id)
                val playback = executor.play("direct", "episode-id")
                assertTrue(playback.supported)
                assertEquals("https://media.example/video.m3u8", playback.url)
                assertEquals("DesktopTest", playback.headers["User-Agent"])
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun compileSpider(directory: Path): Path {
        val source = directory.resolve("Demo.java")
        Files.writeString(source, """
            package com.github.catvod.spider;
            import java.util.List;
            public class Demo {
                public String siteKey;
                public void init(String extension) {
                    if (!"original-provider".equals(siteKey)) throw new IllegalArgumentException("bad provider identity");
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
