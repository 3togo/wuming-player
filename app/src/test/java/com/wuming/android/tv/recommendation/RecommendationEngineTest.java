package com.wuming.android.tv.recommendation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.wuming.android.tv.bean.ViewingRecord;
import com.wuming.android.tv.bean.Vod;
import com.google.gson.Gson;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class RecommendationEngineTest {

    private final Gson gson = new Gson();

    @Test
    public void meaningfulWatchDurationPromotesSimilarTitles() {
        long now = 1_000_000_000L;
        ViewingRecord comedy = record("old", "Comedy", "Alice", 90, now, false);
        ViewingRecord drama = record("drama", "Drama", "Bob", 1, now, false);
        Vod actionCandidate = vod("action", "Action", "Carol");
        Vod comedyCandidate = vod("comedy", "Comedy", "Alice");

        List<Vod> ranked = RecommendationEngine.rank(
                Arrays.asList(actionCandidate, comedyCandidate), "site",
                Arrays.asList(comedy, drama), Collections.emptyList(), now);

        assertEquals("comedy", ranked.get(0).getId());
    }

    @Test
    public void completedTitleIsMovedBehindUnwatchedSuggestions() {
        long now = 1_000_000_000L;
        ViewingRecord watched = record("site@@@watched", "Comedy", "Alice", 60, now, true);
        Vod completedCandidate = vod("watched", "Comedy", "Alice");
        Vod freshCandidate = vod("fresh", "Comedy", "Alice");

        List<Vod> ranked = RecommendationEngine.rank(
                Arrays.asList(completedCandidate, freshCandidate), "site",
                Arrays.asList(watched), Collections.emptyList(), now);

        assertEquals("fresh", ranked.get(0).getId());
        assertEquals("watched", ranked.get(1).getId());
    }

    @Test
    public void shortAccidentalPlaybackDoesNotCreatePreferenceSignal() {
        long now = 1_000_000_000L;
        ViewingRecord shortView = record("short", "Comedy", "Alice", 0, now, false);
        shortView.setWatchedDuration(TimeUnit.SECONDS.toMillis(20));
        Vod first = vod("first", "Drama", "Bob");
        Vod second = vod("second", "Comedy", "Alice");

        List<Vod> ranked = RecommendationEngine.rank(
                Arrays.asList(first, second), "site", Arrays.asList(shortView), Collections.emptyList(), now);

        assertEquals("first", ranked.get(0).getId());
    }

    @Test
    public void emptyAndSingleItemResultsDoNotLoadPreferenceProfile() {
        assertFalse(RecommendationEngine.needsProfile(List.of()));
        assertFalse(RecommendationEngine.needsProfile(List.of(vod("only", "Drama", "Bob"))));
        assertTrue(RecommendationEngine.needsProfile(Arrays.asList(
                vod("first", "Drama", "Bob"), vod("second", "Comedy", "Alice"))));
    }

    private ViewingRecord record(String key, String type, String actor, long minutes, long now, boolean completed) {
        ViewingRecord record = new ViewingRecord();
        record.setKey(key);
        record.setTypeName(type);
        record.setActor(actor);
        record.setWatchedDuration(TimeUnit.MINUTES.toMillis(minutes));
        record.setLastWatchedAt(now);
        record.setCompleted(completed);
        return record;
    }

    private Vod vod(String id, String type, String actor) {
        return gson.fromJson("{\"vod_id\":\"" + id + "\",\"type_name\":\"" + type
                + "\",\"vod_actor\":\"" + actor + "\"}", Vod.class);
    }
}
