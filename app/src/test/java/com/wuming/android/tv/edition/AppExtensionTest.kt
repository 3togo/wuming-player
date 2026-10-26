package com.wuming.android.tv.edition

import org.junit.Assert.*
import org.junit.Test

class AppExtensionTest {
    class ExampleExtension : AppExtension

    @Test fun publicNeedsNoPrivateClasses() {
        val extension = AppExtensions.load("")
        assertTrue(extension.beforeLocalExtractors().isEmpty())
        assertTrue(extension.afterLocalExtractors().isEmpty())
        assertTrue(extension.serverProcesses().isEmpty())
        assertNull(extension.episodeParser("magnet:?xt=fixture"))
    }

    @Test fun configuredProviderLoadsThroughTheSharedContract() {
        assertTrue(AppExtensions.load(ExampleExtension::class.java.name) is ExampleExtension)
    }

    @Test(expected = ClassNotFoundException::class)
    fun missingConfiguredProviderCannotSilentlyDropPrivateFeatures() {
        AppExtensions.load("com.example.DoesNotExist")
    }
}
