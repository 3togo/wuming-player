package com.wuming.android.tv.utils;

import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class FocusOrderTest {

    private final List<Integer> order = List.of(10, 20, 30, 40, 50);

    @Test
    public void skipsUnavailableRowsInBothDirections() {
        Set<Integer> visible = Set.of(10, 40, 50);

        assertEquals(40, FocusOrder.after(order, 10, visible::contains));
        assertEquals(10, FocusOrder.before(order, 40, visible::contains));
    }

    @Test
    public void returnsNoTargetAtBoundariesOrForUnknownCurrentView() {
        Set<Integer> visible = Set.copyOf(order);

        assertEquals(0, FocusOrder.before(order, 10, visible::contains));
        assertEquals(0, FocusOrder.after(order, 50, visible::contains));
        assertEquals(0, FocusOrder.after(order, 99, visible::contains));
    }
}
