package com.wuming.android.tv.media.subtitle;

import com.wuming.tv.core.media.subtitle.SubtitleCandidate;
import com.wuming.tv.core.media.subtitle.SubtitlePage;
import com.wuming.tv.core.media.subtitle.SubtitleProvider;
import com.wuming.tv.core.media.subtitle.SubtitleQuery;
import com.wuming.tv.core.media.subtitle.SubtitleSearchEngine;
import com.wuming.tv.core.media.subtitle.SubtitleSearchResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.URI;
import java.util.List;

public class SubtitleSearchEngineTest {

    @Test
    public void isolatesProviderFailuresAndUsesDeterministicOrder() {
        SubtitleProvider broken = new TestProvider("z-broken") {
            @Override public SubtitlePage search(SubtitleQuery query) throws Exception {
                throw new java.io.IOException("offline");
            }
        };
        SubtitleProvider working = new TestProvider("a-working") {
            @Override public SubtitlePage search(SubtitleQuery query) {
                SubtitleCandidate candidate = new SubtitleCandidate(id(), "1", query.title(),
                        "en", "srt", URI.create("https://example.com/sub.srt"), false, 90);
                return new SubtitlePage(List.of(candidate), 1, 1);
            }
        };
        SubtitleSearchEngine engine = new SubtitleSearchEngine().register(broken).register(working);

        List<SubtitleSearchResult> results = engine.search(SubtitleQuery.movie("Example"));

        assertEquals("a-working", results.get(0).providerId());
        assertTrue(results.get(0).successful());
        assertEquals(1, results.get(0).page().candidates().size());
        assertEquals("z-broken", results.get(1).providerId());
        assertFalse(results.get(1).successful());
        assertEquals("IOException", results.get(1).errorCode());
    }

    private abstract static class TestProvider implements SubtitleProvider {
        private final String id;
        private TestProvider(String id) { this.id = id; }
        @Override public String id() { return id; }
    }
}
