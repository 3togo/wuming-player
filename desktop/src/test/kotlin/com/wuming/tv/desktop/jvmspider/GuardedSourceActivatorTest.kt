package com.wuming.tv.desktop.jvmspider

import com.wuming.tv.core.runtime.jvmspider.JvmSpiderSiteSpec
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardedSourceActivatorTest {

    private val activator = GuardedSourceActivator()

    @Test
    fun `every csp provider is a candidate without claiming compatibility`() {
        assertTrue(activator.isCandidate(site("csp_NewCzGuard")))
        assertTrue(activator.isCandidate(site("csp_DouDouGuard")))
        assertTrue(activator.isCandidate(site("csp_Demo")))
    }

    @Test
    fun `preparation failure records the phase and the original exception`() {
        val artifact = java.nio.file.Files.createTempFile("invalid-guarded-provider-", ".jar")
        val records = java.util.concurrent.CopyOnWriteArrayList<java.util.logging.LogRecord>()
        val logger = java.util.logging.Logger.getLogger("tvbox.guarded.activation")
        val handler = object : java.util.logging.Handler() {
            override fun publish(record: java.util.logging.LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try {
            val failure = runCatching {
                activator.activateAll(listOf(site("csp_Demo").copy(artifactLocation = artifact.toString())),
                    "a".repeat(64), com.wuming.tv.core.runtime.guardspider.GuardedSpiderAbi.ARMEABI_V7A)
            }.exceptionOrNull()
            org.junit.Assert.assertNotNull(failure)
            val record = records.single { it.message.startsWith("activate batch failed") }
            assertTrue(record.message.contains("phase=prepare"))
            org.junit.Assert.assertSame(failure, record.thrown)
        } finally {
            logger.removeHandler(handler)
            java.nio.file.Files.deleteIfExists(artifact)
        }
    }

    private fun site(api: String) = JvmSpiderSiteSpec(
        key = api,
        name = api,
        api = api,
        artifactLocation = "https://example.invalid/spider.jar",
    )
}
