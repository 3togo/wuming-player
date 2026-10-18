package com.wuming.tv.core.source

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordAdmissionTest {

    private data class Row(val key: String, val name: String, val api: String, val url: String)

    private val sites = RecordAdmission.of(
        { row: Row -> row.key },
        { row: Row -> TvBoxRecordFields.siteRejection(row.key, row.api) },
    )

    private val lives = RecordAdmission.of(
        { row: Row -> row.key },
        { row: Row -> TvBoxRecordFields.liveRejection(row.url) },
    )

    private fun doc(field: String, json: String): JsonObject =
        JsonParser.parseString("""{"$field":$json}""").asJsonObject

    private fun row(key: String, name: String = key, api: String = "csp_X", url: String = "http://$key") =
        Row(key, name, api, url)

    @Test
    fun `a section that is absent or unreadable is still a recorded fact`() {
        val absent = sites.admitJson(JsonObject(), "sites") { row("x") }
        assertEquals(SectionOutcome.MISSING, absent.outcome)
        assertTrue(absent.summary().contains("sites section=MISSING records=0"))
        assertFalse(absent.noteworthy)

        val scalar = sites.admitJson(doc("sites", "1"), "sites") { row("x") }
        assertEquals(SectionOutcome.NOT_AN_ARRAY, scalar.outcome)
        assertTrue(scalar.noteworthy)

        val empty = sites.admitJson(doc("sites", "[]"), "sites") { row("x") }
        assertEquals(SectionOutcome.EMPTY, empty.outcome)
        assertTrue(empty.summary().contains("section=EMPTY"))
    }

    @Test
    fun `a lone object section counts as one record`() {
        val admitted = sites.admitJson(doc("sites", """{"key":"one","name":"One","api":"csp_One"}"""), "sites") {
            Row(it.text("key"), it.text("name"), it.text("api"), "")
        }
        assertEquals(SectionOutcome.PRESENT, admitted.outcome)
        assertEquals(listOf("one"), admitted.kept.map { it.key })
    }

    @Test
    fun `records without a usable field are refused for that reason`() {
        val admitted = sites.admitJson(
            doc(
                "sites",
                """[
                    {"key":"ok","name":"Ok","api":"csp_Ok"},
                    {"key":"noApi","name":"NoApi","api":""},
                    {"key":"noName","name":"","api":"csp_N"},
                    {"key":"","name":"NoKey","api":"csp_K"},
                    {"key":"dupe","name":"First","api":"csp_D"},
                    {"key":"dupe","name":"Second","api":"csp_D"}
                ]""",
            ),
            "sites",
        ) { obj -> Row(obj.text("key"), obj.text("name"), obj.text("api"), "") }

        assertEquals(
            listOf(RecordOutcome.KEPT, RecordOutcome.BLANK_API, RecordOutcome.KEPT, RecordOutcome.BLANK_KEY, RecordOutcome.KEPT, RecordOutcome.DUPLICATE),
            admitted.verdicts.map { it.outcome },
        )
        // A site with no caption still has a key to run on, so it stays; the first record for a key wins.
        assertEquals(listOf("ok", "noName", "dupe"), admitted.kept.map { it.key })
        assertEquals("First", admitted.kept.last().name)
        assertTrue(admitted.noteworthy)
    }

    @Test
    fun `one broken record does not take the section down with it`() {
        val admitted = lives.admitJson(
            doc(
                "lives",
                """[{"name":"good","url":"http://a"},{"name":"bad","url":{"nested":1}},{"name":"alsoGood","url":"http://b"}]""",
            ),
            "lives",
        ) { obj -> Row(obj.text("name"), obj.text("name"), "", obj.text("url")) }

        assertEquals(listOf("good", "alsoGood"), admitted.kept.map { it.key })
        assertEquals(RecordOutcome.MALFORMED, admitted.verdicts[1].outcome)
        assertEquals(1, admitted.verdicts[1].position)
        assertTrue(admitted.summary().contains("MALFORMED"))
    }

    @Test
    fun `elements that are not objects are reported instead of dropped`() {
        val admitted = sites.admitJson(doc("sites", """["csp_Text",{"key":"ok","name":"Ok","api":"csp_Ok"}]"""), "sites") {
            Row(it.text("key"), it.text("name"), it.text("api"), "")
        }

        assertEquals(RecordOutcome.NOT_AN_OBJECT, admitted.verdicts[0].outcome)
        assertEquals(listOf("ok"), admitted.kept.map { it.key })
    }

    @Test
    fun `every record read leaves exactly one verdict behind`() {
        val records = listOf(row("a"), row("a"), row(""), row("d"))
        val admitted = sites.admit("sites", records)

        assertEquals(records.size, admitted.verdicts.size)
        assertEquals(
            "sites section=PRESENT records=4 verdicts=[KEPT=2, DUPLICATE=1, BLANK_KEY=1] rejected=2 [#1 DUPLICATE(a), #2 BLANK_KEY()]",
            admitted.summary(),
        )
        assertEquals(listOf("a", "d"), admitted.kept.map { it.key })
    }

    @Test
    fun `decoded lists keep the section outcome the caller knows`() {
        assertEquals(SectionOutcome.EMPTY, sites.admit("lives", emptyList()).outcome)
        assertEquals(SectionOutcome.EMPTY, sites.admit("lives", null).outcome)
        assertEquals(SectionOutcome.MISSING, sites.admit("lives", null, SectionOutcome.MISSING).outcome)
    }

    private fun JsonObject.text(name: String): String = get(name)?.asString ?: ""
}
