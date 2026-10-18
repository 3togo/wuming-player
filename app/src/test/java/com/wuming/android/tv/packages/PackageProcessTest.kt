package com.wuming.android.tv.packages

import com.google.gson.JsonParser
import com.wuming.tv.core.source.packages.SourcePackageLibrary
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Proxy
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

class PackageProcessTest {
    @get:Rule val folder = TemporaryFolder()

    private fun session(params: Map<String,String>, method: NanoHTTPD.Method = NanoHTTPD.Method.POST): NanoHTTPD.IHTTPSession =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(NanoHTTPD.IHTTPSession::class.java)) { _, m, _ -> when(m.name) {
            "getMethod" -> method
            "getParameters" -> params.mapValues { listOf(it.value) }
            else -> null
        } } as NanoHTTPD.IHTTPSession

    private fun body(response: NanoHTTPD.Response) = response.data.bufferedReader().use { it.readText() }

    @Test fun inspectThenInstallRequiresPreviewAndSelectedSources() {
        val library = SourcePackageLibrary(com.wuming.tv.core.source.packages.FilePackageStorage(folder.newFolder("store")))
        val process = PackageProcess(library)
        val zip = folder.newFile("fixture.zip")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("index.json"))
            output.write("""{"sites":[{"key":"demo","type":1,"api":"https://example.invalid/api"}]}""".toByteArray())
            output.closeEntry()
        }
        val inspected = process.doResponse(session(mapOf("package" to "fixture.zip")), "/packages/inspect", mapOf("package" to zip.path))
        assertEquals(200, inspected.status.requestStatus)
        val preview = JsonParser.parseString(body(inspected)).asJsonObject
        assertTrue(library.installed().isEmpty())
        val params = mapOf("token" to preview.get("token").asString,"index" to "0","keys" to "[\"demo\"]","name" to "Fixture")
        val stale = process.doResponse(session(params + ("token" to "wrong")), "/packages/install", emptyMap())
        assertEquals(400, stale.status.requestStatus)
        assertTrue(library.installed().isEmpty())
        val installed = process.doResponse(session(params), "/packages/install", emptyMap())
        assertEquals(200, installed.status.requestStatus)
        assertEquals(listOf("demo"), library.installed().single().current.enabledKeys)
        val list = process.doResponse(session(emptyMap(),NanoHTTPD.Method.GET), "/packages/list", emptyMap())
        assertEquals("Fixture",JsonParser.parseString(body(list)).asJsonArray[0].asJsonObject.get("name").asString)
    }
}
