package com.wuming.android.tv.media.epg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ProgramGuideLayoutTest {

    @Test
    public void clipsSortsAndMarksProgrammesInWindow() {
        ProgramGuideLayout.Channel channel = new ProgramGuideLayout.Channel("c1", "News", List.of(
                new ProgramGuideLayout.Programme("later", "Later", 1_500L, 2_500L),
                new ProgramGuideLayout.Programme("early", "Early", 500L, 1_200L),
                new ProgramGuideLayout.Programme("outside", "Outside", 100L, 900L)));

        ProgramGuideLayout layout = ProgramGuideLayout.create(1_000L, 2_000L, 1_600L,
                List.of(channel));

        assertEquals(2, layout.getRows().get(0).getCells().size());
        ProgramGuideLayout.Cell early = layout.getRows().get(0).getCells().get(0);
        ProgramGuideLayout.Cell later = layout.getRows().get(0).getCells().get(1);
        assertEquals(1_000L, early.getClippedStartMs());
        assertEquals(0.2d, early.getWidth(), 0.0001d);
        assertFalse(early.getCurrent());
        assertEquals(0.5d, later.getOffset(), 0.0001d);
        assertEquals(0.5d, later.getWidth(), 0.0001d);
        assertTrue(later.getCurrent());
        assertEquals(0.6d, layout.getNowOffset(), 0.0001d);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyTimeWindow() {
        ProgramGuideLayout.create(2_000L, 2_000L, 2_000L, List.of());
    }
}
