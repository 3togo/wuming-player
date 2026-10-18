package com.wuming.tv.core.runtime.guardspider

import com.wuming.tv.core.cloud.CloudDriveDetector
import com.wuming.tv.core.cloud.CloudDriveProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GuardedCloudShareDetailTest {
    @Test
    fun `decodes Android cloud search payload without an Activity`() {
        val payload = """{"quark":[{"note":"母荣子贵被冷面赛车手宠上天未删减 (61集) | 短剧","password":"","url":"https://pan.quark.cn/s/demo"}]}"""

        val detail = assertNotNull(GuardedCloudShareDetail.decode("JPan", payload)).let {
            GuardedCloudShareDetail.decode("JPan", payload)!!
        }

        assertEquals("母荣子贵被冷面赛车手宠上天未删减 (61集) | 短剧", detail.item.name)
        assertEquals("Quark", detail.lines.single().name)
        assertEquals("https://pan.quark.cn/s/demo", detail.lines.single().episodes.single().id)
        assertEquals(
            CloudDriveProviderKind.QUARK,
            CloudDriveDetector.detect(
                detail.lines.single().flag,
                detail.lines.single().episodes.single().id,
                detail.lines.single().name,
            ),
        )
    }
}
