package com.wuming.tv.core.library.recommend

import com.wuming.tv.core.catalog.RecommendationCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

private data class FakeRecord(
    override val key: String,
    override val completed: Boolean,
    override val watchedDurationMillis: Long,
    override val lastWatchedAtMillis: Long,
    override val typeName: String = "",
    override val year: String = "",
    override val area: String = "",
    override val director: String = "",
    override val actor: String = "",
    override val content: String = "",
) : ViewingRecord

private data class FakeCandidate(
    override val itemId: String,
    override val typeName: String = "",
    override val year: String = "",
    override val area: String = "",
    override val director: String = "",
    override val actor: String = "",
    override val content: String = "",
) : RecommendationCandidate

class RecommendationPolicyTest {

    @Test
    fun `fewer than two candidates returns input unchanged`() {
        val candidates = listOf(FakeCandidate("a"))
        val result = RecommendationPolicy.rank(candidates, "site", RecommendationPolicy.buildProfile(emptyList(), 0))
        assertEquals(candidates, result)
    }

    @Test
    fun `no viewing signal keeps original order`() {
        val candidates = listOf(FakeCandidate("a"), FakeCandidate("b"))
        val profile = RecommendationPolicy.buildProfile(emptyList(), 0)
        assertFalse(profile.hasSignal)
        assertEquals(listOf("a", "b"), RecommendationPolicy.rank(candidates, "site", profile).map { it.itemId })
    }

    @Test
    fun `prefers candidates matching learned type`() {
        val now = 1_000_000L
        val records = listOf(
            FakeRecord("site@@@x", false, TimeUnit.MINUTES.toMillis(40), now - TimeUnit.DAYS.toMillis(1), typeName = "动作"),
        )
        val candidates = listOf(
            FakeCandidate("a", typeName = "剧情"),
            FakeCandidate("b", typeName = "动作"),
            FakeCandidate("c", typeName = "纪录片"),
        )
        val profile = RecommendationPolicy.buildProfile(records, now)
        assertTrue(profile.hasSignal)
        val ranked = RecommendationPolicy.rank(candidates, "site", profile).map { it.itemId }
        assertEquals("b", ranked.first())
    }

    @Test
    fun `penalises already completed items`() {
        val now = 1_000_000L
        val records = listOf(
            FakeRecord("site@@@b", true, TimeUnit.MINUTES.toMillis(90), now - TimeUnit.DAYS.toMillis(1), typeName = "动作"),
        )
        val candidates = listOf(
            FakeCandidate("b", typeName = "动作"),
            FakeCandidate("c", typeName = "动作"),
        )
        val profile = RecommendationPolicy.buildProfile(records, now)
        val ranked = RecommendationPolicy.rank(candidates, "site", profile).map { it.itemId }
        // "b" was completed before, so the un-watched "c" should win despite equal type weight.
        assertEquals("c", ranked.first())
    }

    @Test
    fun `ignores records outside the profile window`() {
        val now = 1_000_000L
        val stale = listOf(
            FakeRecord("site@@@x", false, TimeUnit.MINUTES.toMillis(40), now - TimeUnit.DAYS.toMillis(400), typeName = "动作"),
        )
        val fresh = listOf(
            FakeRecord("site@@@x", false, TimeUnit.MINUTES.toMillis(40), now - TimeUnit.DAYS.toMillis(1), typeName = "动作"),
        )
        assertFalse(RecommendationPolicy.buildProfile(stale, now).hasSignal)
        assertTrue(RecommendationPolicy.buildProfile(fresh, now).hasSignal)
    }

    @Test
    fun `completed candidates do not also receive the watched penalty`() {
        val now = TimeUnit.DAYS.toMillis(500)
        val records = List(38) {
            FakeRecord(
                "site@@@b",
                true,
                TimeUnit.MINUTES.toMillis(240),
                now,
                typeName = "action",
            )
        }
        val candidates = listOf(
            FakeCandidate("b", typeName = "action"),
            FakeCandidate("c", typeName = "drama"),
        )

        val ranked = RecommendationPolicy.rank(
            candidates,
            "site",
            RecommendationPolicy.buildProfile(records, now),
        )

        assertEquals("b", ranked.first().itemId)
    }

    @Test
    fun `corrupt minimum timestamp expires without arithmetic overflow`() {
        val records = listOf(
            FakeRecord(
                key = "site@@@old",
                completed = false,
                watchedDurationMillis = TimeUnit.MINUTES.toMillis(40),
                lastWatchedAtMillis = Long.MIN_VALUE,
                typeName = "action",
            ),
        )

        assertFalse(RecommendationPolicy.buildProfile(records, 1_000L).hasSignal)
    }

    @Test
    fun `future timestamp survives a clock rollback without negative age`() {
        val records = listOf(
            FakeRecord(
                key = "site@@@future",
                completed = false,
                watchedDurationMillis = TimeUnit.MINUTES.toMillis(40),
                lastWatchedAtMillis = 2_000L,
                typeName = "action",
            ),
        )

        assertTrue(RecommendationPolicy.buildProfile(records, 1_000L).hasSignal)
    }
}
