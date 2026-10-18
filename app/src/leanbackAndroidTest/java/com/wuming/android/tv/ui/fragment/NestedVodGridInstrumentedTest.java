package com.wuming.android.tv.ui.fragment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wuming.android.tv.LeanbackProduct;
import com.wuming.android.tv.bean.Style;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class NestedVodGridInstrumentedTest {

    @Test
    public void nestedFolderCannotInheritSingleColumnListStyle() {
        Style resolved = LeanbackTypeFragment.resolveDisplayStyle(Style.list(), true);

        assertFalse(resolved.isList());
        assertTrue(resolved.isRect());
        assertTrue(LeanbackProduct.getColumn(resolved) > 1);
    }

    @Test
    public void topLevelListAndExistingGridStylesRemainUnchanged() {
        Style list = Style.list();
        Style grid = Style.rect();

        assertSame(list, LeanbackTypeFragment.resolveDisplayStyle(list, false));
        assertSame(grid, LeanbackTypeFragment.resolveDisplayStyle(grid, true));
    }
}
