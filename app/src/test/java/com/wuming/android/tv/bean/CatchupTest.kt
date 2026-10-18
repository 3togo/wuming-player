package com.wuming.android.tv.bean

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupTest {

    private fun epg(startMs: Long, endMs: Long): EpgData =
        EpgData().also {
            it.startTime = startMs
            it.endTime = endMs
        }

    private fun catchup(
        type: String?,
        source: String?,
        regex: String?,
        replace: String?,
        days: String? = null,
    ): Catchup = Catchup.create().also {
        it.setType(type)
        it.setSource(source)
        it.setRegex(regex)
        it.setReplace(replace)
        it.setDays(days)
    }

    @Test
    fun nullFieldsReadBackAsEmptyStrings() {
        val item = Catchup.create()
        assertEquals("", item.getType())
        assertEquals("", item.getDays())
        assertEquals("", item.getRegex())
        assertEquals("", item.getReplace())
        assertEquals("", item.getSource())
        assertTrue(item.isEmpty())
    }

    @Test
    fun emptyIsASharedInstanceAndOnlySourceMakesACatchupUsable() {
        assertSame(Catchup.empty(), Catchup.empty())
        assertTrue(Catchup.empty().isEmpty())
        val item = Catchup.create()
        item.setDays("7")
        item.setType("append")
        assertTrue(item.isEmpty())
        item.setSource("?t=1")
        assertFalse(item.isEmpty())
    }

    @Test
    fun decideKeepsTheMajorSourceAndFallsBackToTheMinorOne() {
        val major = catchup("append", "?major=1", null, null)
        val minor = catchup("append", "?minor=1", null, null)
        assertSame(major, Catchup.decide(major, minor))
        assertSame(minor, Catchup.decide(Catchup.empty(), minor))
        assertSame(minor, Catchup.decide(null, minor))
        assertNull(Catchup.decide(null, null))
        assertNull(Catchup.decide(Catchup.empty(), Catchup.empty()))
    }

    @Test
    fun pltvPresetCarriesTheProviderPlayseekContract() {
        val item = Catchup.PLTV()
        assertEquals("7", item.getDays())
        assertEquals("append", item.getType())
        assertEquals("/PLTV/", item.getRegex())
        assertEquals("/PLTV/,/TVOD/", item.getReplace())
        assertTrue(item.getSource().startsWith("?playseek="))
        assertFalse(item.isEmpty())
    }

    @Test
    fun aCatchupWithoutAPatternNeverMatches() {
        assertFalse(catchup("append", "?t=1", "", "").match("https://live.example/PLTV/index.m3u8"))
        assertTrue(catchup("append", "?t=1", "/PLTV/", "").match("https://live.example/PLTV/index.m3u8"))
    }

    /**
     * Every field gets a distinct sentinel, so a slot swapped while building the template changes
     * the result. The token and query rules themselves belong to CatchupUrlPolicyTest in epg-core.
     */
    @Test
    fun formatPassesEachFieldIntoItsOwnTemplateSlot() {
        val item = catchup(
            "append",
            "?seek=\${(b)timestamp}..\${(e)timestamp}",
            "/CHANNEL-A/",
            "/CHANNEL-A/,/CHANNEL-B/",
        )
        assertEquals(
            "https://live.example/CHANNEL-B/index.m3u8?seek=1..5",
            item.format("https://live.example/CHANNEL-A/index.m3u8", epg(1000L, 5000L)),
        )
        // "default" is the one type the policy treats specially, so it confirms the type slot too.
        assertEquals(
            "?seek=5",
            catchup("default", "?seek=\${(e)timestamp}", null, null)
                .format("https://live.example/index.m3u8", epg(1000L, 5000L)),
        )
    }

    @Test
    fun gsonDeserializesProviderFieldNames() {
        val item = Gson().fromJson(
            """{"type":"view","days":"3","regex":"/x/","replace":"/x/,/y/","source":"?s=1"}""",
            Catchup::class.java,
        )
        assertNotNull(item)
        assertEquals("view", item.getType())
        assertEquals("3", item.getDays())
        assertEquals("/x/", item.getRegex())
        assertEquals("/x/,/y/", item.getReplace())
        assertEquals("?s=1", item.getSource())
    }
}
