package com.wuming.tv.core.playback

import org.junit.Assert.*
import org.junit.Test

class MpvConfigurationPolicyTest {
    @Test fun defaultProfileAliasesCommentsAndBom() {
        val text = "\uFEFF# comment\nno-border\n--speed=1.5\n[cinema]\nvo=other\n[default] # back\nosc=no\ninvalid option=yes"
        assertEquals(setOf("border", "speed", "osc"), MpvConfigurationPolicy.defaultOptions(text))
        assertEquals(listOf("border", "osc", "speed"), MpvConfigurationPolicy.conflicts(text))
        assertEquals(emptyList<String>(), MpvConfigurationPolicy.conflicts("deband=yes\nslang=fr\n[custom]\nfullscreen=yes"))
    }
}
