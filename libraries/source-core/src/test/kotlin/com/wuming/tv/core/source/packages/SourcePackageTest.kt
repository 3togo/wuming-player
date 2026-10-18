package com.wuming.tv.core.source.packages

import com.google.gson.JsonParser
import com.wuming.tv.core.source.TvBoxConfigPayloadDecoder
import com.wuming.tv.core.source.config.ConfigResourceResolver
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SourcePackageTest {
    private fun archive(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
    private fun candidate(value: String = "one"): PackageCandidate {
        val json = """{"spider":"./plugin.png;md5;${digest("dex".toByteArray(), "MD5")}","sites":[{"key":"push_agent","name":"$value","type":3,"api":"csp_Push"},{"key":"js","name":"JS","type":3,"api":"./rule.js","ext":{"file":"./%E5.js","code":"return '../literal'"}}]}"""
        return SourcePackageInspector.inspect(mapOf("index.json" to json.toByteArray(), "origin.json" to json.toByteArray(), "plugin.png" to "dex".toByteArray(), "rule.js" to byteArrayOf(1), "%E5.js" to byteArrayOf(2))).single()
    }
    @Test fun rejectsTraversalAndAbsoluteArchivePaths() {
        listOf("../outside", "/absolute", "a/../../b", "a\\b", "C:/bad").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { SourcePackageInspector.archive(archive(name to byteArrayOf(1)).inputStream()) }
        }
    }
    @Test fun rejectsOversizedCompressedEntry() {
        val compressed = archive("bomb" to ByteArray(SourcePackageInspector.MAX_FILE_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { SourcePackageInspector.archive(compressed.inputStream()) }
    }
    @Test fun discoversPackagesWithoutCountingOriginsOrExecutingScripts() {
        val one = candidate()
        assertTrue(one.errors.toString(), one.errors.isEmpty())
        val all = one.files.mapKeys { "one/${it.key}" } + one.files.mapKeys { "two/${it.key}" }
        assertEquals(listOf("one", "two"), SourcePackageInspector.inspect(all).map { it.name })
    }
    @Test fun diagnosesMissingAndMismatchedResources() {
        val files = candidate().files.toMutableMap()
        files.remove("rule.js"); files["plugin.png"] = "different".toByteArray()
        val errors = SourcePackageInspector.inspect(files).single().errors.joinToString()
        assertTrue(errors.contains("rule.js")); assertTrue(errors.contains("Checksum mismatch"))
    }
    @Test fun inspectsDisguisedArchivesAndUnknownGuards() {
        val files = candidate().files + ("image.jpg" to archive("classes.dex" to byteArrayOf(1), "assets/woshinidie.bin" to byteArrayOf(2)))
        val item = SourcePackageInspector.inspect(files).single()
        assertTrue(item.artifacts.containsKey("image.jpg"))
        assertTrue(item.warnings.any { it.contains("Unsupported desktop") })
    }
    @Test fun validatesCompoundExtPathsSeparately() {
        val json = """{"sites":[{"key":"x","ext":"./pg.json${'$'}${'$'}${'$'}https://provider.test/${'$'}${'$'}${'$'}cfg${'$'}${'$'}${'$'}./missing.txt"}]}"""
        val item = SourcePackageInspector.inspect(mapOf("index.json" to json.toByteArray(), "pg.json" to "{}".toByteArray())).single()
        assertEquals(1, item.errors.size); assertTrue(item.errors.single().contains("missing.txt"))
    }
    @Test fun scopesIdentitiesAndRetainsOriginalProviderKey() {
        val a = PackageSourceIdentity.scoped("a".repeat(32), "push_agent:中文")
        val b = PackageSourceIdentity.scoped("b".repeat(32), "push_agent:中文")
        assertNotEquals(a, b); assertEquals("push_agent:中文", PackageSourceIdentity.original(a))
        assertEquals("ordinary", PackageSourceIdentity.original("ordinary"))
    }
    @Test fun installUpdateRestartRollbackAndIdempotence() {
        val storage = MemoryStorage(); val library = SourcePackageLibrary(storage)
        val one = library.install(candidate(), "Example", listOf("js"))
        assertEquals(one, library.install(candidate(), "Example", listOf("js")))
        val two = library.install(candidate("two"), "Example", listOf("js", "push_agent"))
        assertEquals(one.id, two.id); assertNotEquals(one.current.version, two.current.version)
        val restarted = SourcePackageLibrary(storage)
        assertEquals(one.current, restarted.rollback(one.id).current)
        val json = JsonParser.parseString(restarted.configuration(one.id, "http://127.0.0.1/files/${one.id}")).asJsonObject
        assertEquals(one.id, json.get("wumingPackageId").asString)
        val sites = json.getAsJsonArray("sites")
        assertEquals(1, sites.size())
        assertEquals("js", sites[0].asJsonObject.get("key").asString)
        assertTrue(sites[0].asJsonObject.getAsJsonObject("ext").get("file").asString.endsWith("/%25E5.js"))
        assertEquals("return '../literal'", sites[0].asJsonObject.getAsJsonObject("ext").get("code").asString)
    }
    @Test fun httpOnlySelectionDoesNotPreloadRootPlugin() {
        val original = candidate()
        val config = original.config.deepCopy()
        config.add("sites", JsonParser.parseString("""[{"key":"http","type":1,"api":"https://example.invalid/vod"},{"key":"binary","type":3,"api":"csp_Test"}]"""))
        val candidate = SourcePackageInspector.inspect(original.files + ("index.json" to config.toString().toByteArray())).single()
        val library = SourcePackageLibrary(MemoryStorage())
        val installed = library.install(candidate,"HTTP",listOf("http"))
        val generated = JsonParser.parseString(library.configuration(installed.id,"http://127.0.0.1/files")).asJsonObject
        assertFalse(generated.has("spider"))
        assertEquals(1,generated.getAsJsonArray("sites").size())
    }

    @Test fun pythonOnlySelectionDoesNotPreloadRootDex() {
        val original = candidate()
        val config = original.config.deepCopy()
        config.add("sites", JsonParser.parseString("""[{"key":"python","type":3,"api":"https://example.invalid/spider.py"}]"""))
        val inspected = SourcePackageInspector.inspect(original.files + ("index.json" to config.toString().toByteArray())).single()
        val library = SourcePackageLibrary(MemoryStorage())
        val installed = library.install(inspected, "Python", listOf("python"))
        val generated = JsonParser.parseString(library.configuration(installed.id, "http://127.0.0.1/files")).asJsonObject
        assertFalse(generated.has("spider"))
        assertEquals("python", generated.getAsJsonArray("sites")[0].asJsonObject.get("key").asString)
    }

    @Test fun updatingTheSameNamedPackageRetainsItsEnabledSelection() {
        val library = SourcePackageLibrary(MemoryStorage())
        val candidate = candidate()
        library.install(candidate, candidate.name, listOf("js"))
        assertEquals(listOf("js"), library.initialSelection(candidate))
    }

    @Test fun failedPublishLeavesPreviousSelectionIntact() {
        val storage = MemoryStorage(); val library = SourcePackageLibrary(storage)
        val first = library.install(candidate(), "Example", listOf("js"))
        storage.failPublish = true
        assertThrows(IllegalStateException::class.java) { library.install(candidate("two"), "Example", listOf("js")) }
        assertEquals(first, library.installed().single())
    }
    @Test fun changedFilesAndEscapingResourceRequestsAreRejected() {
        val storage = MemoryStorage(); val library = SourcePackageLibrary(storage)
        val first = library.install(candidate(), "Example", listOf("js"))
        assertThrows(IllegalArgumentException::class.java) { library.resource(first.id, first.current.version, "../index.json") }
        storage.versions[first.current.version] = mapOf("bad" to byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { library.configuration(first.id, "http://localhost") }
    }
    @Test fun resolvesUrisWithoutCorruptingQueriesOrOpaqueData() {
        val base = "https://example.test/pack/config.json"
        assertEquals("https://example.test/js/%E5%AD%90.js?x=../data.json#part;md5;abc", ConfigResourceResolver.resolve(base,"../js/%E5%AD%90.js?x=../data.json#part;md5;abc"))
        assertEquals("https://example.test/pack/%E4%B8%AD%20%E6%96%87.js", ConfigResourceResolver.resolve(base,"./中 文.js"))
        assertEquals("https://mirror.test/https://raw.example/a/js/x.js", ConfigResourceResolver.resolve("https://mirror.test/https://raw.example/a/config.json", "./js/x.js"))
        assertEquals("file:/tmp/pack/lib/x.js", ConfigResourceResolver.resolve("file:///tmp/pack/index.json", "./lib/x.js"))
        val root = JsonParser.parseString("""{"sites":[{"ext":"some ../opaque text","api":"./a.js?x=../b"}],"notice":"./not-a-file"}""").asJsonObject
        val fixed = ConfigResourceResolver.rewrite(root) { ConfigResourceResolver.resolve(base,it) }
        assertEquals("./not-a-file", fixed.get("notice").asString)
        assertEquals("some ../opaque text", fixed.getAsJsonArray("sites")[0].asJsonObject.get("ext").asString)
        assertTrue(fixed.getAsJsonArray("sites")[0].asJsonObject.get("api").asString.endsWith("?x=../b"))
    }
    @Test fun decodesCbcAfterImageBase64Envelope() {
        val json = """{"sites":[{"key":"synthetic"}]}"""
        val key = "123456"; val iv = "123456789abcd"
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key.padEnd(16,'0').toByteArray(),"AES"), javax.crypto.spec.IvParameterSpec(iv.padEnd(16,'0').toByteArray()))
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
        val encoded = "2423" + hex(key.toByteArray()) + "2324" + hex(cipher.doFinal(json.toByteArray())) + hex(iv.toByteArray())
        val envelope = byteArrayOf(0x89.toByte(),0x50,0x4e,0x47) + ("Ab12Cd34**" + Base64.getEncoder().encodeToString(encoded.toByteArray())).toByteArray()
        assertEquals(json, TvBoxConfigPayloadDecoder.decode(envelope))
        assertThrows(IllegalArgumentException::class.java) { TvBoxConfigPayloadDecoder.decode("2423bad".toByteArray()) }
    }
    private class MemoryStorage : SourcePackageStorage {
        var index: String? = null
        var failPublish = false
        val versions = mutableMapOf<String, Map<String, ByteArray>>()
        override fun readIndex() = index
        override fun publishIndex(json: String) { check(!failPublish); index = json }
        override fun writeVersion(id: String, version: String, files: Map<String, ByteArray>) { versions[version] = files }
        override fun readVersion(id: String, version: String) = versions.getValue(version)
        override fun readResource(id: String, version: String, path: String) = versions.getValue(version).getValue(path)
    }
}
