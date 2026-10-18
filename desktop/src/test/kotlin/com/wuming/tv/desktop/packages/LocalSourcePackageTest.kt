package com.wuming.tv.desktop.packages

import com.wuming.tv.core.source.packages.FilePackageStorage
import com.wuming.tv.core.source.packages.SourcePackageInspector
import com.wuming.tv.core.source.packages.SourcePackageLibrary
import com.wuming.tv.core.runtime.js.GraalJsCatalogSourceExecutor
import com.wuming.tv.desktop.config.DesktopTvBoxConfigurationLoader
import com.wuming.tv.desktop.net.DesktopResourceLoader
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI

class LocalSourcePackageTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun localConfigCanExecuteSyntheticJsThroughScopedHttpBridge() {
        val root = folder.newFolder("local")
        File(root,"index.json").writeText("""{"sites":[{"key":"demo","type":3,"api":"./demo.js","ext":"./%E5.txt"},{"key":"py","type":3,"api":"./script.py"}]}""")
        File(root,"%E5.txt").writeText("fixture")
        File(root,"demo.js").writeText("""export default {init: function(ext) {}, home: function(){return {class:[],list:[{vod_id:'1',vod_name:'Local fixture'}]};}};""")
        val config = DesktopTvBoxConfigurationLoader(cache = null).load(File(root,"index.json").toURI().toString())
        val spec = config.sites.first().execution!!
        assertTrue(spec.moduleUrl.startsWith("http://127.0.0.1:"))
        assertEquals("fixture", DesktopResourceLoader(logRequests = false).load(spec.extension).toString(Charsets.UTF_8))
        GraalJsCatalogSourceExecutor(spec).use { executor -> assertEquals("Local fixture", executor.home().single().name) }
        assertNull(config.sites[1].jvmExecution)
        val outside = folder.newFile("outside.txt").apply { writeText("private") }
        val module = URI(spec.moduleUrl)
        val escape = "${module.scheme}://${module.authority}${module.path.substringBeforeLast('/')}/%2e%2e/outside.txt"
        assertThrows(Exception::class.java) { DesktopResourceLoader(logRequests = false).load(escape) }
        assertTrue(outside.exists())
    }
    @Test fun filesystemInstallSurvivesRestartAndRollback() {
        val root = folder.newFolder("store")
        fun candidate(name: String) = SourcePackageInspector.inspect(mapOf("index.json" to """{"sites":[{"key":"x","name":"$name","type":1,"api":"https://example.invalid/api"}]}""".toByteArray())).single()
        val first = SourcePackageLibrary(FilePackageStorage(root)).install(candidate("one"), "My package", listOf("x"))
        val second = SourcePackageLibrary(FilePackageStorage(root)).install(candidate("two"), "My package", listOf("x"))
        assertEquals(first.id, second.id)
        val other = SourcePackageLibrary(FilePackageStorage(root)).install(candidate("other"), "Other package", listOf("x"))
        val retained = DesktopSourcePackages.retainedJsStorageIdentities(SourcePackageLibrary(FilePackageStorage(root)).installed())
        for (item in listOf(second, other)) {
            assertTrue(retained.contains(com.wuming.tv.core.source.packages.PackageSourceIdentity.scoped(item.id, "x") + "\u0000package://${item.id}/x"))
        }
        assertEquals(2, retained.size)
        val third = SourcePackageLibrary(FilePackageStorage(root)).rollback(first.id)
        assertEquals(first.current, third.current)
        assertTrue(File(root, "${first.id}/${first.current.version}/index.json").exists())
    }
    @Test fun managedMetadataScopesHostKeysButKeepsProviderKeys() {
        val json = """{"wumingPackageId":"${"a".repeat(32)}","spider":"./p.jar","sites":[{"key":"push_agent","type":3,"api":"csp_Push"},{"key":"js","type":3,"api":"./a.js"}]}"""
        val config = DesktopTvBoxConfigurationLoader(cache=null, loadResource={json.toByteArray()}).load("https://example.invalid/config.json")
        assertTrue(config.sites[0].key.startsWith("wmp:")); assertTrue(config.sites[0].inputOnly)
        assertEquals("push_agent", config.sites[0].jvmExecution!!.providerKey)
        assertEquals("js", config.sites[1].execution!!.providerKey)
    }
}
