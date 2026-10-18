package com.wuming.tv.core.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDriveDetectorTest {
    @Test
    fun detectsProviderFromLineNameOrAddress() {
        assertEquals(CloudDriveProviderKind.UC, CloudDriveDetector.detect("opaque", "encrypted", "优汐智1"))
        assertEquals(CloudDriveProviderKind.QUARK, CloudDriveDetector.detect("", "https://pan.quark.cn/s/share", ""))
        assertEquals(CloudDriveProviderKind.ALI, CloudDriveDetector.detect("阿狸原画", "opaque", ""))
        assertEquals(CloudDriveProviderKind.BAIDU, CloudDriveDetector.detect("百度网盘", "opaque", ""))
        assertEquals(CloudDriveProviderKind.BAIDU, CloudDriveDetector.detect("", "https://pan.baidu.com/s/share", ""))
        assertEquals(CloudDriveProviderKind.NONE, CloudDriveDetector.detect("BD5", "https://media.example/video.m3u8", "direct"))
    }

    @Test
    fun detectsNewProvidersFromAnalysis() {
        assertEquals(CloudDriveProviderKind.PAN123, CloudDriveDetector.detect("123云盘", "opaque", ""))
        assertEquals(CloudDriveProviderKind.PAN123, CloudDriveDetector.detect("", "https://123pan.com/s/abc", ""))
        assertEquals(CloudDriveProviderKind.TIANYI, CloudDriveDetector.detect("天翼云盘", "opaque", ""))
        assertEquals(CloudDriveProviderKind.TIANYI, CloudDriveDetector.detect("", "https://cloud189.cn/s/abc", ""))
        assertEquals(CloudDriveProviderKind.WEBDAV, CloudDriveDetector.detect("webdav", "opaque", ""))
        assertEquals(CloudDriveProviderKind.PAN115, CloudDriveDetector.detect("115网盘", "opaque", ""))
        assertEquals(CloudDriveProviderKind.PAN115, CloudDriveDetector.detect("", "https://115.com/s/abc", ""))
        assertEquals(CloudDriveProviderKind.XUNLEI, CloudDriveDetector.detect("迅雷云盘", "opaque", ""))
        assertEquals(CloudDriveProviderKind.XUNLEI, CloudDriveDetector.detect("", "https://pan.xunlei.com/s/abc", ""))
        assertEquals(CloudDriveProviderKind.PIKPAK, CloudDriveDetector.detect("pikpak", "opaque", ""))
        assertEquals(CloudDriveProviderKind.PIKPAK, CloudDriveDetector.detect("", "https://mypikpak.com/s/abc", ""))
    }

    @Test
    fun accountRequiredMessagesAreMachineClassifiable() {
        val message = CloudDriveDetector.accountRequiredMessage(CloudDriveProviderKind.UC)
        assertTrue(CloudDriveDetector.isAccountRequiredMessage(message))
    }

    @Test
    fun supportedProvidersGetSignInMessage() {
        val message = CloudDriveDetector.accountRequiredMessage(CloudDriveProviderKind.QUARK)
        assertTrue("Supported provider should mention Sign in", message.contains("Sign in"))
    }

    @Test
    fun unsupportedProvidersGetNotYetSupportedMessage() {
        val message = CloudDriveDetector.accountRequiredMessage(CloudDriveProviderKind.PAN115)
        assertTrue("Unsupported provider should mention not yet supported", message.contains("not yet supported"))
        val xunleiMessage = CloudDriveDetector.accountRequiredMessage(CloudDriveProviderKind.XUNLEI)
        assertTrue(xunleiMessage.contains("not yet supported"))
        val pikpakMessage = CloudDriveDetector.accountRequiredMessage(CloudDriveProviderKind.PIKPAK)
        assertTrue(pikpakMessage.contains("not yet supported"))
    }

    @Test
    fun hasOpenDriveProviderCorrectlyClassifies() {
        assertTrue(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.QUARK))
        assertTrue(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.ALI))
        assertTrue(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.UC))
        assertTrue(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.BAIDU))
        assertTrue(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.PAN123))
        assertTrue(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.TIANYI))
        assertTrue(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.WEBDAV))
        assertFalse(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.PAN115))
        assertFalse(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.XUNLEI))
        assertFalse(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.PIKPAK))
        assertFalse(CloudDriveDetector.hasOpenDriveProvider(CloudDriveProviderKind.NONE))
    }
}
