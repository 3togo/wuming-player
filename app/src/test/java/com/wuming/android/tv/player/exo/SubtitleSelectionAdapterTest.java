package com.wuming.android.tv.player.exo;

import com.wuming.android.tv.bean.Sub;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

public class SubtitleSelectionAdapterTest {
    @Test public void externalSubtitleFlagsPreserveProviderIntent() throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("zh-TW"));
            Sub simplified = Sub.create("Simplified", "https://example.test/a.srt", "zh-Hans", "application/x-subrip");
            Sub traditional = Sub.create("Traditional", "https://example.test/b.srt", "zh-Hant", "application/x-subrip");
            List<Integer> flags = ExoUtil.subtitleSelectionFlags(List.of(simplified, traditional));
            assertEquals(List.of(4, 1), flags);
            simplified.setFlag(2);
            flags = ExoUtil.subtitleSelectionFlags(List.of(simplified, traditional));
            assertEquals(List.of(2, 4), flags);
            assertEquals(0, traditional.getRawFlag());
        } finally { Locale.setDefault(previous); }
    }
}
