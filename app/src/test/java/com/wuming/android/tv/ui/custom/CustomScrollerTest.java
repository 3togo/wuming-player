package com.wuming.android.tv.ui.custom;

import static org.junit.Assert.*;

import com.wuming.android.tv.bean.Result;
import com.wuming.android.tv.bean.Vod;
import java.util.List;
import org.junit.Test;

public class CustomScrollerTest {
    @Test public void resetRestoresPagingWithoutClearingAnActiveLoad() {
        CustomScroller scroller = new CustomScroller(null);
        scroller.setPage(3);
        scroller.setEnable(3);
        scroller.setLoading(true);
        assertTrue(scroller.isDisable());
        scroller.reset();
        assertTrue(scroller.first());
        assertFalse(scroller.isDisable());
        assertTrue(scroller.isLoading());
        assertEquals(2, scroller.addPage());
    }

    @Test public void emptyPageRollsBackButNonemptyPageKeepsItsNumber() {
        CustomScroller scroller = new CustomScroller(null);
        Result result = new Result();
        result.setList(List.of());
        scroller.addPage();
        scroller.setLoading(true);
        scroller.endLoading(result);
        assertTrue(scroller.first());
        assertFalse(scroller.isLoading());
        assertFalse(scroller.isDisable());
        scroller.addPage();
        result.setList(List.of(new Vod()));
        scroller.endLoading(result);
        assertEquals(3, scroller.addPage());
    }

    @Test public void unknownPageCountKeepsPaginationEnabled() {
        CustomScroller scroller = new CustomScroller(null);
        scroller.setPage(10);
        scroller.setEnable(10);
        assertTrue(scroller.isDisable());
        scroller.setEnable(11);
        assertFalse(scroller.isDisable());
        scroller.setEnable(0);
        assertFalse(scroller.isDisable());
    }
}
