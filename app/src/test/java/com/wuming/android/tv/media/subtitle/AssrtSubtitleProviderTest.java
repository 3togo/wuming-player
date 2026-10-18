package com.wuming.android.tv.media.subtitle;

import com.wuming.tv.core.media.subtitle.SubtitleCandidate;
import com.wuming.tv.core.media.subtitle.SubtitlePage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AssrtSubtitleProviderTest {

    @Test
    public void rejectsMissingTokensAndForeignOrNullCandidatesBeforeNetworkAccess() {
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> new AssrtSubtitleProvider(null));
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> new AssrtSubtitleProvider(" \t"));
        AssrtSubtitleProvider provider = new AssrtSubtitleProvider("fixture");
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> provider.resolve(null));
        SubtitleCandidate foreign = new SubtitleCandidate("other", "42", "Example", "", "", null, false, 0);
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> provider.resolve(foreign));
    }

    @Test
    public void parsesOfficialSearchShapeWithoutTokenOrUrls() throws Exception {
        String json = "{\"status\":0,\"sub\":{\"subs\":[{"
                + "\"id\":594867,\"native_name\":\"Example\","
                + "\"subtype\":\"Subrip(srt)\",\"vote_score\":88,"
                + "\"lang\":{\"desc\":\"English\"}}]}}";

        SubtitlePage page = AssrtSubtitleProvider.parseSearch(json, 1);

        assertEquals(1, page.candidates().size());
        SubtitleCandidate candidate = page.candidates().get(0);
        assertEquals("594867", candidate.id());
        assertEquals("srt", candidate.format());
        assertEquals("English", candidate.language());
        assertEquals(88, candidate.matchScore());
        assertEquals(null, candidate.downloadUri());
    }

    @Test
    public void prefersSupportedOnTheFlyFileFromDetail() throws Exception {
        SubtitleCandidate source = new SubtitleCandidate("assrt", "602333", "Example", "双语",
                "", null, false, 50);
        String json = "{\"status\":0,\"sub\":{\"subs\":[{"
                + "\"filename\":\"archive.rar\",\"url\":\"https://example.com/archive.rar\","
                + "\"filelist\":[{\"f\":\"Example.srt\","
                + "\"url\":\"https://example.com/Example.srt?sign=secret\"}]}]}}";

        SubtitleCandidate resolved = AssrtSubtitleProvider.parseDetail(json, source);

        assertEquals("srt", resolved.format());
        assertFalse(resolved.archive());
        assertTrue(resolved.downloadUri().toString().contains("Example.srt"));
    }
}
