package com.wuming.tv.core.runtime.python

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PythonRuntimeBundleTest {
    @get:Rule val folder = TemporaryFolder()
    private fun zip(name: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { it.putNextEntry(ZipEntry(name)); it.write("fixture".toByteArray()); it.closeEntry() }
    }.toByteArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    @Test fun installsAtomicallyAndReusesExactVersion() {
        val bytes = zip("lib/python3.13/fixture.py")
        val home = PythonRuntimeBundle.install(folder.root, digest(bytes)) { bytes.inputStream() }
        assertEquals("fixture", File(home, "lib/python3.13/fixture.py").readText())
        assertEquals(home, PythonRuntimeBundle.install(folder.root, digest(bytes)) { error("must not reopen") })
    }
    @Test fun corruptAndTraversingBundlesCannotPublish() {
        val safe = zip("file")
        assertThrows(IllegalArgumentException::class.java) { PythonRuntimeBundle.install(folder.root, "0".repeat(64)) { safe.inputStream() } }
        val traversal = zip("../escaped")
        assertThrows(IllegalArgumentException::class.java) { PythonRuntimeBundle.install(folder.root, digest(traversal)) { traversal.inputStream() } }
        assertEquals(0, folder.root.listFiles()!!.size)
    }
}
