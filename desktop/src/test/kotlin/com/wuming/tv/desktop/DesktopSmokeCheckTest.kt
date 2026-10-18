package com.wuming.tv.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopSmokeCheckTest {

    @Test
    fun verifiesEveryGenericLibraryBoundary() {
        val report = DesktopSmokeCheck.verify()

        assertEquals(13, report.components.size)
        assertTrue("catalog-core" in report.components)
        assertTrue("playback-core" in report.components)
        assertTrue("cloud-core" in report.components)
        assertTrue("cloud-login-runtime-core" in report.components)
        assertTrue("live-core" in report.components)
        assertTrue("js-runtime-core" in report.components)
        assertTrue("jvm-spider-runtime-core" in report.components)
        assertTrue("guarded-spider-runtime-core" in report.components)
    }
}
