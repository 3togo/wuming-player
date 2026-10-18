package com.wuming.android.tv.player.engine;

import static org.junit.Assert.*;

import com.wuming.android.tv.bean.Danmaku;
import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Sub;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaySpecTest {

    @Test
    public void headersBelongToThePlaybackInsteadOfTheProviderResult() {
        Map<String, String> headers = new HashMap<>(Map.of("user-agent", "provider"));
        PlaySpec spec = PlaySpec.from("one", "https://example.test/one", headers, null);
        headers.put("Cookie", "later-account");
        spec.getHeaders().put("Referer", "playback-only");
        spec.checkUa();
        assertFalse(spec.getHeaders().containsKey("Cookie"));
        assertFalse(headers.containsKey("Referer"));
        assertEquals("provider", spec.getHeaders().get("user-agent"));
        assertFalse(spec.getHeaders().containsKey("User-Agent"));
    }

    @Test
    public void replacingHeadersCopiesImmutableInputAndAcceptsNull() {
        PlaySpec spec = PlaySpec.from("one", "https://example.test/one", null, null);
        Map<String, String> immutable = Map.of("User-Agent", "provider");
        spec.setHeaders(immutable);
        spec.checkUa();
        spec.getHeaders().put("Referer", "new");
        assertEquals(1, immutable.size());
        spec.setHeaders(null);
        assertTrue(spec.getHeaders().isEmpty());
    }

    @Test
    public void addingSubtitlesDoesNotMutateAnImmutableProviderList() {
        Sub original = Sub.create("English", "https://example.test/en.vtt", "en", "text/vtt");
        Sub added = Sub.create("French", "https://example.test/fr.vtt", "fr", "text/vtt");
        List<Sub> subtitles = List.of(original);
        PlaySpec spec = fromLists(subtitles, List.of());
        spec.setSub(added);
        spec.setSub(added);
        assertEquals(List.of(added, original), spec.getSubs());
        assertEquals(List.of(original), subtitles);
    }

    @Test
    public void overlaySelectionIsIsolatedBetweenPlaybackSessions() {
        Danmaku item = Danmaku.from("https://example.test/overlay.xml");
        List<Danmaku> overlays = List.of(item);
        PlaySpec first = fromLists(List.of(), overlays);
        PlaySpec second = fromLists(List.of(), overlays);
        first.setDanmaku(item);
        assertTrue(first.getDanmakus().get(0).isSelected());
        assertFalse(second.getDanmakus().get(0).isSelected());
        assertFalse(item.isSelected());
        first.setDanmaku(null);
        assertFalse(first.getDanmakus().get(0).isSelected());
    }

    @Test
    public void overlayCanBeAddedWhenProviderReturnedAnImmutableEmptyList() {
        PlaySpec spec = fromLists(List.of(), List.of());
        Danmaku item = Danmaku.from("https://example.test/new.xml");
        spec.setDanmaku(item);
        spec.setDanmaku(item);
        assertEquals(1, spec.getDanmakus().size());
        assertTrue(spec.getDanmakus().get(0).isSelected());
        assertFalse(item.isSelected());
    }

    private static PlaySpec fromLists(List<Sub> subtitles, List<Danmaku> overlays) {
        Result result = new Result() {
            @Override public List<Sub> getSubs() { return subtitles; }
            @Override public List<Danmaku> getDanmaku() { return overlays; }
        };
        return PlaySpec.fromParse(result, "test", null);
    }
}
