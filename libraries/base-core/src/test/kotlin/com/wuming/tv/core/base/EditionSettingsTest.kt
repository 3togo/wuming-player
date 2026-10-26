package com.wuming.tv.core.base

import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class EditionSettingsTest {
    @Test fun absentConfigurationRetainsPublicIdentity() {
        val settings = EditionSettings.from(Properties())
        assertEquals("com.wuming.android.tv.publicedition", settings.applicationId)
        assertEquals("com.wuming.tv.guardwebview.publicedition/com.wuming.tv.guardwebview.MainActivity", settings.helperComponent("guardwebview"))
        assertFalse(settings.nativeProtocols)
        assertEquals(listOf("http", "https", "video"), settings.pushSchemes)
    }

    @Test fun privateIdentityIsSharedByBothHelperTargets() {
        val settings = EditionSettings.from(Properties().apply {
            setProperty("applicationId", "com.wuming.android.tv.privateedition")
            setProperty("helperSuffix", "privateedition")
            setProperty("nativeProtocols", "true")
            setProperty("pushSchemes", "http,https,thunder,magnet,ed2k,video")
        })
        assertTrue(settings.nativeProtocols)
        assertTrue(settings.pushSchemes.contains("magnet"))
        for (name in listOf("guardspider", "guardwebview")) {
            assertEquals("com.wuming.tv.$name.privateedition", settings.helperPackage(name))
            assertEquals("${settings.helperPackage(name)}/com.wuming.tv.$name.MainActivity", settings.helperComponent(name))
        }
    }
}
